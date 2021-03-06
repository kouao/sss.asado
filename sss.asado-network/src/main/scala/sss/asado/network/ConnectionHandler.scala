package sss.asado.network

import java.net.InetSocketAddress
import java.util.Base64

import akka.actor.{Actor, ActorLogging, ActorRef, SupervisorStrategy}
import akka.io.Tcp
import akka.io.Tcp._
import akka.util.{ByteString, CompactByteString}
import com.google.common.primitives.Longs

import scala.util.{Failure, Success}

case class NodeId(id: String, inetSocketAddress: InetSocketAddress)
case class Connection(nodeId: NodeId, handlerRef: ActorRef)
case class IncomingNetworkMessage(nodeId: NodeId, nm: NetworkMessage)
case class NetworkMessage(msgCode: Byte, data: Array[Byte])

case object CloseConnection

class ConnectionHandler(
                         nonce: Long,
                         connection: ActorRef,
                         remote: InetSocketAddress,
                         netInf: NetworkInterface
                       ) extends Actor with Buffering with ActorLogging with Protocol {

  context watch connection

  override def preStart: Unit = connection ! ResumeReading

  override def postStop(): Unit = log.info(s"Connection handler $self down")

  // there is not recovery for broken connections
  override val supervisorStrategy = SupervisorStrategy.stoppingStrategy

  private def processErrors: Receive = {
    case CommandFailed(w: Write) =>
      log.warning(s"Write failed :$w $remote")
      connection ! ResumeWriting

    case cc: ConnectionClosed =>
      log.debug(s"Connection closed to : $remote ${Option(cc.getErrorCause)}")
      context.stop(self)

    case CloseConnection =>
      log.debug(s"Programmer enforced connection close with: $remote")
      connection ! Close

    case CommandFailed(cmd: Tcp.Command) =>
      log.warning(s"Failed to execute command : $cmd ")
      connection ! ResumeReading
      connection ! ResumeWriting
  }


  private var sentHandShake = false
  private var remoteIdOpt: Option[String] = None

  private def handshake : Receive = {

    case h: Handshake =>
      connection ! Write(ByteString(h.bytes))

    case Received(data) =>
      Handshake.parse(data.toArray) match {
        case Success(shake) =>
          if(shake.fromNonce == nonce) {
            // this is my nonce returned, check he signed it correctly.
            val handshakeGood = netInf.handshakeVerifier.verify(shake.sig, Longs.toByteArray(shake.fromNonce), shake.nodeId, shake.tag)
            if (handshakeGood) {
              val delay = (System.currentTimeMillis() / 1000) - shake.time
              log.debug(s"Got a Handshake from $remote, delay in s is $delay")
              remoteIdOpt = Some(shake.nodeId)

              if(sentHandShake) {
                val nId = NodeId(shake.nodeId, remote)
                context.parent ! Connection(nId, self)
                context become working(nId)
              }

            } else {
              log.info(s"Got a bad handshake from ${shake.fromAddress}, closing.")
              connection ! Close
            }

          } else {

            val mySig = netInf.handshakeVerifier.sign(Longs.toByteArray(shake.fromNonce))
            val signedShake = netInf.createHandshake(shake.fromNonce, mySig)
            val sigStr = Base64.getEncoder.encodeToString(signedShake.sig)
            log.info(s"Signing ${signedShake.fromNonce} ${signedShake.nodeId}, ${signedShake.tag}, ${sigStr}")
            connection ! Write(ByteString(signedShake.bytes))
            sentHandShake = true

            remoteIdOpt map { remoteId =>
                val nId = NodeId(remoteId, remote)
                context.parent ! Connection(nId, self)
                context become working(nId)
            }
          }
          connection ! ResumeReading

        case Failure(t) =>
          log.info(s"Error parsing a handshake: $t")
          connection ! Close
      }

  }

  private var chunksBuffer: ByteString = CompactByteString()

  def handleMessages(nId: NodeId): Receive = {
    case m@NetworkMessage(msgCode, data) =>
      val bytes = toWire(m)
      connection ! Write(bytes)

    case Received(data) =>

      val t = getPacket(chunksBuffer ++ data)
      chunksBuffer = t._2

      t._1.find { packet =>
        fromWire(packet.toByteBuffer) match {
          case Success(message) =>
            context.parent ! IncomingNetworkMessage(nId,message)
            false

          case Failure(e) =>
            log.info(s"Corrupted data from: " + remote, e)
            //connection ! Close
            //  context stop self
            true
        }
      }
      connection ! ResumeReading
  }

  def working(nId: NodeId): Receive =
    handleMessages(nId) orElse
      processErrors orElse ({
      case nonsense: Any =>
        log.warning(s"Strange input for ConnectionHandler: $nonsense")
    }: Receive)

  override def receive: Receive = handshake
}


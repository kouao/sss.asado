package sss.asado.http


import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, Props}
import org.scalatra.{BadRequest, Ok, ScalatraServlet}
import sss.asado.actor.AsadoEventSubscribedActor
import sss.asado.balanceledger.{BalanceLedger, TxIndex, TxOutput}
import sss.asado.block._
import sss.asado.identityledger.Claim
import sss.asado.ledger._
import sss.asado.network.NetworkMessage
import sss.asado.state.AsadoStateProtocol.{NotReadyEvent, ReadyStateEvent}
import sss.asado.util.ByteArrayEncodedStrOps._
import sss.asado.wallet.IntegratedWallet
import sss.asado.wallet.IntegratedWallet.{Payment, TxFailure, TxSuccess}
import sss.asado.{MessageKeys, PublishedMessageKeys}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Promise}
import scala.language.postfixOps
import scala.util.{Failure, Success, Try}
/**
  * Created by alan on 5/7/16.
  */
class ClaimServlet(actorSystem:ActorSystem,
                   stateMachine: ActorRef,
                   messageRouter: ActorRef,
                   balanceLedger: BalanceLedger,
                   integratedWallet: IntegratedWallet) extends ScalatraServlet {

  import ClaimServlet._

  val crlf = System.getProperty("line.separator")

  implicit val timeout = Duration(30, SECONDS)

  private val claimsActor = actorSystem.actorOf(Props(classOf[ClaimsResultsActor], stateMachine, messageRouter, integratedWallet))

  get("/balanceledger") {

    Ok(balanceLedger.map( _ match {
      case TxOutput(amount, enc) => s"$amount locked to $enc"
    }).mkString(crlf))

  }

  get("/debit") {
    params.get("to") match {
      case None => BadRequest("Param 'to' not found")
      case Some(to) => params.get("amount") match {
        case None => BadRequest("Param 'amount' not found")
        case Some(amount) =>
          integratedWallet.pay(Payment(to, amount.toInt)) match {
            case TxSuccess(blkTxId, txIndex, _) =>
              Ok(txIndex.toString() + "Balance now - " + integratedWallet.balance)
            case TxFailure(TxMessage(_, _, str), _) => Ok(str + "Balance now - " + integratedWallet.balance)
          }

      }
    }
  }

  get("/") {
    params.get("claim") match {
      case None => BadRequest("Param 'claim' not found")
      case Some(claiming) => params.get("pKey") match {
        case None => BadRequest("Param 'pKey' not found")
        case Some(pKeyStr) =>
            val claim = Claim(claiming, pKeyStr.toByteArray)
            val ste = SignedTxEntry(claim.toBytes)
            val le = LedgerItem(MessageKeys.IdentityLedger , claim.txId, ste.toBytes)
            val p = Promise[String]()
            claimsActor ! Claiming(claiming, le.txIdHexStr, NetworkMessage(PublishedMessageKeys.SignedTx, le.toBytes), p)
            p.future.map { Ok(_) }
            Await.result(p.future, Duration(1, MINUTES))

      }
    }
  }
}

object ClaimServlet {

  case class Claiming(identity: String, txIdHex: String, netMsg: NetworkMessage, p: Promise[String])
  case class ClaimTracker(sendr: ActorRef, claiming: Claiming)
}

class ClaimsResultsActor(stateMachine: ActorRef, messageRouter: ActorRef, integratedWallet: IntegratedWallet)
  extends Actor with ActorLogging with AsadoEventSubscribedActor {

  import ClaimServlet._
  var inFlightClaims: Map[String, ClaimTracker] = Map()

  val kickStartingAmount = 100



  private def working: Receive = {

    case NotReadyEvent => context.become(init)

    case c@Claiming(identity, txIdHex, netMsg, promise) =>
      inFlightClaims += (txIdHex-> ClaimTracker(sender(), c))
      messageRouter ! netMsg

    case NetworkMessage(MessageKeys.AckConfirmTx, bytes) =>
      val bcTxId = bytes.toBlockChainIdTx
      val hexId = bcTxId.blockTxId.txId.toBase64Str
      val trackerOpt = inFlightClaims.get(hexId)
      Try {
        trackerOpt map { claimTracker =>
          integratedWallet.payAsync(self, Payment(claimTracker.claiming.identity, kickStartingAmount, Option(hexId), 1, 0))
        }
      } match {
        case Failure(e) =>
          log.info(e.getMessage)

          trackerOpt.map{ claimTracker =>
            if(claimTracker.claiming.p.isCompleted) log.info(s"${hexId} already completed ")
            else claimTracker.claiming.p.success(s"fail:${e.getMessage}")
          }
        case Success(_) =>
      }

    case NetworkMessage(MessageKeys.TempNack, bytes) =>
      Try {
        val txMsg = bytes.toTxMessage
        val hexId = txMsg.txId.toBase64Str
        val claimTracker = inFlightClaims(hexId)
        context.system.scheduler.scheduleOnce(5 seconds, messageRouter, claimTracker.claiming.netMsg)
      } match {
        case Success(_) =>
        case Failure(e) => log.warning(e.toString)
      }



    case NetworkMessage(MessageKeys.SignedTxNack, bytes) =>
      val txMsg = bytes.toTxMessage
      val hexId = txMsg.txId.toBase64Str
      val claimTracker = inFlightClaims(hexId)
      inFlightClaims -= hexId
      if(claimTracker.claiming.p.isCompleted) log.info(s"${hexId} already completed ")
      else claimTracker.claiming.p.success(s"fail:${txMsg.msg}")

    case TxSuccess(blockChainTxId, txIndex, txTracking) =>
      inFlightClaims.get(txTracking.get) map { claimTracker =>
        log.info(s"Got TxSuccess for $txTracking")
        val indx = TxIndex(blockChainTxId.blockTxId.txId, 0)
        if(claimTracker.claiming.p.isCompleted) log.info(s"${txTracking.get} already completed ")
        else claimTracker.claiming.p.success(s"ok:${txIndex.toString}:$kickStartingAmount:${blockChainTxId.height}")
      }
    case TxFailure(txMsg, txTracking) =>
      inFlightClaims.get(txTracking.get) map { claimTracker =>
        if(claimTracker.claiming.p.isCompleted) log.info(s"${txTracking.get} already completed ")
        else claimTracker.claiming.p.success(s"okNoMoney:${txMsg.msg}")
      }
  }

  override def postStop = log.info("ClaimsResultsActor has stopped")

  override def receive: Receive = init

  def init: Receive = {
    case c@Claiming(identity, txIdHex, netMsg, promise) => promise.success("fail:Network not ready")

    case ReadyStateEvent => context.become(working)
  }

}

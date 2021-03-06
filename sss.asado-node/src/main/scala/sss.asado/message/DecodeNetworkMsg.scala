package sss.asado.message

import akka.actor.{Actor, ActorLogging}
import sss.asado.network.NetworkMessage

import scala.util.{Failure, Success, Try}

/**
  * Created by alan on 6/24/16.
  */
trait DecodeNetworkMsg {
  self : Actor with ActorLogging =>

  def apply[T](f: => T)(work: T => Unit)(fail: => (Byte, Array[Byte])) = {
    Try {
      work(f)
    } match {
      case Success(_) =>
      case Failure(e) =>
        log.error(e, "Generic failure handler")
        sender() ! NetworkMessage(fail._1, fail._2)
    }
  }
}
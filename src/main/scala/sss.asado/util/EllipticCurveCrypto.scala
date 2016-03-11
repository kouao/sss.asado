package sss.asado.util

import scorex.crypto.signatures.Curve25519
import scorex.crypto.signatures.SigningFunctions.{Signature, MessageToSign}
import sss.asado.account.PrivateKeyAccount

/**
  * This implementation is being used from many places in the code. We consider easy switching from one
  * EC implementation from another as possible option, while switching to some other signature schemes
  * (e.g. hash-based signatures) will require a lot of code changes around the project(at least because of
  * big signature size).
  */
object EllipticCurveCrypto extends Curve25519 {
  def sign(account: PrivateKeyAccount, message: MessageToSign): Signature = sign(account.privateKey, message)
}

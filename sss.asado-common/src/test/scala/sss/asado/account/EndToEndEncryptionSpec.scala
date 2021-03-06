package sss.asado.account

import java.nio.charset.StandardCharsets

import org.scalatest.{FlatSpec, Matchers}
import scorex.crypto.signatures.Curve25519
import sss.asado.crypto.{CBCEncryption, SeedBytes}
import sss.asado.util.ByteArrayComparisonOps


/**
  * Created by alan on 2/11/16.
  */
class EndToEndEncryptionSpec extends FlatSpec with Matchers with ByteArrayComparisonOps {

  lazy val pkPair1 = PrivateKeyAccount(SeedBytes(32))
  lazy val pkPair2 = PrivateKeyAccount(SeedBytes(32))

  lazy val message = "There once was a man from Nantucket, and rode her like Billy the Kid"

  "End to End encryption " should " successfully encrypt and decrypted with a public/private shared secret " in {

    val sharedSecret = Curve25519.createSharedSecret(pkPair1.privateKey, pkPair2.publicKey)
    val iv = CBCEncryption.newInitVector

    val encrypted = CBCEncryption.encrypt(sharedSecret, message, iv)

    val reverseSharedSecret = Curve25519.createSharedSecret(pkPair2.privateKey, pkPair1.publicKey)
    val reconstitutedIv = CBCEncryption.initVector(iv.asString)

    assert(reverseSharedSecret.sameElements(sharedSecret))

    val dencrypted = CBCEncryption.decrypt(reverseSharedSecret, encrypted, reconstitutedIv)

    assert(dencrypted.sameElements(message.getBytes))
    assert(new String(dencrypted, StandardCharsets.UTF_8) == message)

  }

}

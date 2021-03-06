package sss.asado.ledger.serialize

import org.scalatest.{FlatSpec, Matchers}
import sss.asado.account.PrivateKeyAccount
import sss.asado.balanceledger._
import sss.asado.contract.{PrivateKeySig, SinglePrivateKey}
import sss.asado.crypto.SeedBytes
import sss.asado.ledger.TxId

/**
  * Created by alan on 2/15/16.
  */

class TxTest extends FlatSpec with Matchers {

  val randomTxId: TxId = SeedBytes(32)
  val txIndex = TxIndex(randomTxId, 3456)
  val pkPair = PrivateKeyAccount()
  val txOutput = TxOutput(33, SinglePrivateKey(pkPair.publicKey))
  val txInput = TxInput(txIndex, 34, PrivateKeySig)
  val tx = StandardTx(Seq(txInput, txInput, txInput), Seq(txOutput, txOutput, txOutput))

  "A Tx" should " be parseable to bytes " in {
    val bytes: Array[Byte] = tx.toBytes
  }

  it should " be parseable from bytes to an equal instance " in {
    val bytes: Array[Byte] = tx.toBytes
    val backAgain = bytes.toTx
    assert(backAgain === tx)
  }

}

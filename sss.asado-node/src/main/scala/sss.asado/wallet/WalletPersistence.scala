package sss.asado.wallet

import java.util.Date

import sss.asado.balanceledger._
import sss.asado.contract.ContractSerializer._
import sss.asado.ledger._
import sss.asado.util.ByteArrayEncodedStrOps._
import sss.db._

/**
  * Created by alan on 6/28/16.
  */

object WalletPersistence {


  private val idCol = "id"
  private val txIdCol = "txid"
  private val txIdIndxCol = "txid_indx"
  private val statusCol = "status_col"
  private val createdAtCol = "created_at"
  private val amountCol = "amount"
  private val encumbranceCol = "encumbrance"
  private val blockHeightCol = "block_height"
  private val unSpent = 0
  private val spent = 1

  case class Lodgement(txIndex: TxIndex, txOutput: TxOutput, inBlock: Long)

}

class WalletPersistence(uniqueTag :String, db: Db) {

  import WalletPersistence._

  private lazy val tableName = s"wallet_$uniqueTag"

  def tx[T](f: => T): T = db.tx[T](f)

  def markSpent(txIndex: TxIndex) = tx {
    table.toLongIdOpt(txIdCol -> txIndex.txId.toBase64Str, txIdIndxCol -> txIndex.index).map { id =>
      table.update(Map(idCol -> id,
        txIdIndxCol -> txIndex.index,
        statusCol -> spent))
    }
  }


  def track(lodgement: Lodgement) = {

    table.insert(Map(txIdCol -> lodgement.txIndex.txId.toBase64Str,
      txIdIndxCol -> lodgement.txIndex.index,
      amountCol -> lodgement.txOutput.amount,
      encumbranceCol -> lodgement.txOutput.encumbrance.toBytes,
      blockHeightCol -> lodgement.inBlock,
      createdAtCol -> new Date().getTime,
      statusCol -> unSpent
    ))
  }

  def listUnSpent: Seq[Lodgement] = tx {
    table.filter(
      where(s"$statusCol = ?") using (unSpent))
      .map(r => Lodgement(TxIndex(r[String](txIdCol).asTxId, r[Int](txIdIndxCol)), TxOutput(r[Int](amountCol), r[Array[Byte]](encumbranceCol).toEncumbrance), r[Long](blockHeightCol)))
  }


  private val createTableSql =
    s"""CREATE TABLE IF NOT EXISTS ${tableName}
        |($idCol BIGINT GENERATED BY DEFAULT AS IDENTITY (START WITH 1, INCREMENT BY 1),
        |$txIdCol VARCHAR(64),
        |$txIdIndxCol INT,
        |$statusCol INT,
        |$encumbranceCol BLOB,
        |$amountCol INT DEFAULT 0,
        |$blockHeightCol BIGINT,
        |$createdAtCol BIGINT,
        |PRIMARY KEY($txIdCol, $txIdIndxCol));
        |""".stripMargin

  db.executeSql(createTableSql)

  private val table = db.table(tableName)


}

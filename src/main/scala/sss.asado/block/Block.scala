package sss.asado.block

import block.{BlockTx, BlockTxId}
import com.twitter.util.SynchronizedLruMap
import ledger._
import sss.ancillary.Logging
import sss.asado.util.ByteArrayVarcharOps._
import sss.db.{Db, OrderAsc, Row, Where}

import scala.annotation.tailrec
import scala.util.{Failure, Success, Try}


object Block extends Logging {
  private val blockTableNamePrefix = "block_"
  private lazy val blockCache = new SynchronizedLruMap[Long, Block](10)
  private def makeTableName(height: Long) = s"$blockTableNamePrefix$height"
  def apply(height: Long)(implicit db:Db): Block = blockCache.getOrElseUpdate(height, new Block(height))

  private[block] def findSmallestMissing(candidates: Seq[Long]): Long = {

    @tailrec
    def hasNext(candis: Seq[Long], count: Long): Long = {
      candis match {
        case Seq() =>  count
        case head +: rest if(head == count + 1) => hasNext(rest, count + 1)
        case _ => count
      }
    }
    hasNext(candidates, 0)
  }
}

class Block(val height: Long)(implicit db:Db) extends Logging {

  import Block._

  val tableName = makeTableName(height)
  private val id = "id"
  private val txid = "txid"
  private val entry = "entry"
  private val committed = "committed"

  db.executeSql (s"CREATE TABLE IF NOT EXISTS $tableName (" +
    s"id BIGINT GENERATED BY DEFAULT AS IDENTITY (START WITH 0, INCREMENT BY 1), " +
    s"txid VARCHAR(64) NOT NULL, entry BLOB, " +
    s"committed BOOLEAN DEFAULT FALSE NOT NULL, " +
    s"confirm INT DEFAULT 0, " +
    s"PRIMARY KEY(id), UNIQUE(txid))")


  private val blockTxTable = db.table(tableName)

  private[block] def truncate: Unit = db.executeSql(s"TRUNCATE TABLE $tableName")

  def entries: Seq[BlockTx] = {
    blockTxTable.map (toBlockTx, OrderAsc(id))
  }

  private def toBlockTx(r: Row): BlockTx = BlockTx(r[Long](id), r[Array[Byte]](entry).toSignedTx)

  def page(index: Long, pageSize: Int): Seq[Array[Byte]] = {
    var count = 0
    blockTxTable.page(index, pageSize, Seq(OrderAsc(id))) map { r =>
      require(r[Long](id) == index + count)
      count += 1
      r[Array[Byte]](entry)
    }
  }

  def maxMonotonicIndex: Long = {
    val allIds = blockTxTable.map(r => (r[Long](id), r[Boolean](committed)), OrderAsc(id)).filter(_._2).map(_._1)
    findSmallestMissing(allIds)
  }

  def apply(k: TxId): BlockTx = get(k).get

  def count = blockTxTable.count

  def inTransaction[T](f: => T): T = blockTxTable.inTransaction[T](f)

  def get(id: TxId): Option[BlockTx] = blockTxTable.find(Where(s"$txid = ?", id.toVarChar)).map(toBlockTx)

  def write(index: Long, k: TxId, le: SignedTx): Long = {
    val bs = le.toBytes
    val hexStr = k.toVarChar
    val row = blockTxTable.insert(Map(id -> index, txid -> hexStr, entry -> bs))
    row(id)
  }

  def commit(index: Long): Unit = {
    blockTxTable.update(Map(id -> index, committed -> true))
  }

  def writeCommitted(k: TxId, le: SignedTx): Long = {
    val bs = le.toBytes
    val hexStr = k.toVarChar
    val row = blockTxTable.persist(Map(txid -> hexStr, entry -> bs, committed -> true))
    row(id)
  }

  def getUnconfirmed(requiredConfirms: Int): Seq[(Int, BlockTx)] = {
    val all = blockTxTable.filter(Where("confirm < ?", requiredConfirms)) map (row => (row[Int]("confirm"), toBlockTx(row)))
    if(all.size > 0) {
      log.info(s"Print ALL unconfirmed ${all.size}, required($requiredConfirms)")
      all.foreach {case (conf: Int, btx: BlockTx) => log.info(s"Not enough confirms:$conf ${btx.toString}")}
    }

    all
  }

  def confirm(blockTxId: BlockTxId): Unit = {
    Try {
      val hex = blockTxId.txId.toVarChar
      val rowsUpdated = blockTxTable.update("confirm = confirm + 1", s"txid = '$hex'")
      require(rowsUpdated == 1, s"Must update 1 row, by confirming the tx, not $rowsUpdated rows")

    } match {
      case Failure(e) => log.error(s"FAILED to add confirmation!", e)
      case Success(r) => log.info(s"Tx confirmed. $r")
    }
  }
}

package sss.asado.block

import com.twitter.util.SynchronizedLruMap
import sss.ancillary.Logging
import sss.asado.ledger._
import sss.asado.util.ByteArrayEncodedStrOps._
import sss.db.{Db, OrderAsc, Row, Where}

import scala.annotation.tailrec
import scala.util.{Failure, Success, Try}


object Block extends Logging {
  private val blockTableNamePrefix = "block_"
  private lazy val blockCache = new SynchronizedLruMap[Long, Block](100)
  private def makeTableName(height: Long) = s"$blockTableNamePrefix$height"
  def apply(height: Long)(implicit db:Db): Block = blockCache.getOrElseUpdate(height, new Block(height))

  def drop(height: Long)(implicit db:Db) = {
    val tblName = makeTableName(height)
    Try(db.executeSql(s"DROP TABLE $tblName")) match {
      case Failure(e) => log.debug(s"Exception, table ${tblName} probably doesn't exist.")
      case Success(_) =>
    }
  }

  private[block] def findSmallestMissing(candidates: Seq[Long]): Long = {

    @tailrec
    def hasNext(candis: Seq[Long], count: Long): Long = {
      candis match {
        case Seq() =>  count
        case head +: rest if(head == count + 1) => hasNext(rest, count + 1)
        case _ => count
      }
    }
    hasNext(candidates, -1)
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
    s"PRIMARY KEY(id), UNIQUE(txid));")


  private val blockTxTable = db.table(tableName)

  private[block] def truncate: Unit = db.executeSql(s"TRUNCATE TABLE $tableName")

  def entries: Seq[BlockTx] = {
    blockTxTable.map (toBlockTx, OrderAsc(id))
  }

  private def toBlockTx(r: Row): BlockTx = BlockTx(r[Long](id), r[Array[Byte]](entry).toLedgerItem)

  def page(index: Long, pageSize: Int): Seq[Array[Byte]] = {
    var count = 0
    blockTxTable.page(index, pageSize, Seq(OrderAsc(id))) map { r =>
      require(r[Long](id) == index + count)
      count += 1
      r[Array[Byte]](entry)
    }
  }

  /**
    *
    * @return the index of the last tx committed in order (0,1,2..)
    *         if NO tx has been committed -1 is returned
    *         if only the first Tx has been committed, the smallest index is 0
    *         if the first Tx has NOT been committed, the smallest is -1
    */
  def maxMonotonicCommittedIndex: Long = {
    val allIds = blockTxTable.map(r => (r[Long](id), r[Boolean](committed)), OrderAsc(id)).filter(_._2).map(_._1)
    findSmallestMissing(allIds)
  }

  def apply(k: TxId): BlockTx = get(k).get

  def count = blockTxTable.count

  def inTransaction[T](f: => T): T = blockTxTable.inTransaction[T](f)

  def get(id: TxId): Option[BlockTx] = blockTxTable.find(Where(s"$txid = ?", id.toBase64Str)).map(toBlockTx)

  def journal(index: Long, le: LedgerItem): Long = {
    val bs = le.toBytes
    val hexStr = le.txId.toBase64Str
    val row = blockTxTable.insert(Map(id -> index, txid -> hexStr, entry -> bs))
    row(id)
  }

  def getUnCommitted: Seq[BlockTx] = blockTxTable.filter(Where(s"$committed IS FALSE ORDER BY $id ASC")) map (toBlockTx)

  def commit(index: Long): Unit = {
    blockTxTable.update(Map(id -> index, committed -> true))
  }

  def write(le: LedgerItem): Long = {
    val bs = le.toBytes
    val hexStr: String = le.txId.toBase64Str
    val row = blockTxTable.persist(Map(txid -> hexStr, entry -> bs, committed -> true))
    row(id)
  }

  def getUnconfirmed(requiredConfirms: Int): Seq[(Int, BlockTx)] = {
    blockTxTable.filter(Where("confirm < ?", requiredConfirms)) map (row => (row[Int]("confirm"), toBlockTx(row)))
  }

  def confirm(blockTxId: BlockTxId): Try[Int] = {
    Try {
      val hex: String = blockTxId.txId.toBase64Str
      val rowsUpdated = blockTxTable.update("confirm = confirm + 1", s"txid = '$hex'")
      require(rowsUpdated == 1, s"Must update 1 row, by confirming the tx, not $rowsUpdated rows")
      rowsUpdated
    } recover {
      case e => log.error(s"FAILED to add confirmation!", e); throw e
    }
  }
}

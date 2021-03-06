package sss.asado.message

import java.util.Date

import com.twitter.util.SynchronizedLruMap
import org.joda.time.LocalDateTime
import sss.db._

/**
  * Created by alan on 6/6/16.
  */
object MessagePersist {

  private val idCol = "id"
  private val metaInfoCol = "meta_info"
  private val fromCol = "from_col"
  private val statusCol = "status_col"
  private val messageCol = "message"
  private val txCol = "tx_col"
  private val createdAtCol = "created_at"

  private val statusPending  = 0
  private val statusAccepted = 1
  private val statusRejected = 2

  private val messageTableNamePrefix = "message_"
  private def makeMessageTableName(identity: Identity): Identity = messageTableNamePrefix + identity.toLowerCase
  private lazy val tableCache = new SynchronizedLruMap[Identity, MessagePersist](500)


  def apply(identity: Identity)(implicit db:Db): MessagePersist = {
    val tableName = makeMessageTableName(identity)
    fromName(tableName)
  }

  private def fromName(tableName: String)(implicit db:Db): MessagePersist =
    tableCache.getOrElseUpdate(tableName, new MessagePersist(tableName))


}

class MessagePersist(tableName: String)(implicit val db: Db) {

  import MessagePersist._


  db.executeSql (s"CREATE TABLE IF NOT EXISTS $tableName (" +
    s"$idCol BIGINT GENERATED BY DEFAULT AS IDENTITY (START WITH 1, INCREMENT BY 1), " +
    s"$fromCol VARCHAR(100), " +
    s"$statusCol INT, " +
    s"$metaInfoCol VARCHAR(100), " +
    s"$messageCol BLOB, " +
    s"$txCol BLOB, " +
    s"$createdAtCol BIGINT, " +
    s"PRIMARY KEY($idCol));")

  lazy private val table = db.table(tableName)


  private def toMsg(r:Row): Message = Message(
    r[String](fromCol),
     r[Array[Byte]](messageCol),
    r[Array[Byte]](txCol),
    r[Long](idCol),
    new LocalDateTime(r[Long](createdAtCol)))


  def pending(from: String, msg: Array[Byte], tx: Array[Byte]): Long = {
    val row = table.insert(Map(
      metaInfoCol -> None,
      fromCol -> from,
      messageCol -> msg,
      statusCol -> statusPending,
      txCol -> tx,
      createdAtCol -> new Date().getTime))
    row[Long](idCol)
  }

  def accept(index: Long): Unit = {
    table.update(Map(statusCol -> statusAccepted, idCol -> index))
  }

  def reject(index: Long): Boolean = table.delete(where(s"$idCol = ?") using (index)) == 1

  def page(lastReadindex: Long, pageSize: Int): Seq[Message] = {
    table.filter(
      where(s"$idCol > ? AND $statusCol = $statusAccepted ORDER BY $idCol ASC LIMIT $pageSize")
        using(lastReadindex)).map(toMsg)
  }

  def delete(index: Long): Boolean = table.delete(where(s"$idCol = ?") using(index)) == 1

  def maxIndex: Long = table.maxId

}
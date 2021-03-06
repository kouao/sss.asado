package sss.asado.identityledger


import java.sql.SQLIntegrityConstraintViolationException
import java.util.Date

import org.joda.time.DateTime
import scorex.crypto.signatures.SigningFunctions.PublicKey
import sss.asado.account.PublicKeyAccount
import sss.db._

import scala.util.{Failure, Success, Try}

/**
  * Created by alan on 5/30/16.
  */

trait IdentityServiceQuery {
  val defaultTag = IdentityService.defaultTag
  def matches(identity: String, publicKey: PublicKey): Boolean
  def identify(publicKey: PublicKey): Option[TaggedIdentity]
  def account(identity: String, tag: String = defaultTag): PublicKeyAccount
  def accounts(identity: String): Seq[TaggedPublicKeyAccount]
  def accountOpt(identity: String, tag: String = defaultTag): Option[PublicKeyAccount]
  def rescuers(identity: String): Seq[String]

}

trait IdentityService extends IdentityServiceQuery {
  def claim(identity: String, publicKey: PublicKey, tag: String = defaultTag)
  def link(identity: String, publicKey: PublicKey, tag: String)
  def unlink(identity: String, publicKey: PublicKey): Boolean
  def unlink(identity: String, tag: String): Boolean
  def linkRescuer(identity: String, rescuer: String)
  def unLinkRescuer(identity: String, rescuer: String)
}

object IdentityService {

  val defaultTag = "defaultTag"

  private val id = "id"
  private val identityCol = "identity_col"
  private val identityLnkCol = "identity_lnk"
  private val createdCol = "created_dt"
  private val publicKeyCol = "public_key"
  private val tagCol = "tag_col"
  private val domainCol = "domain_col"
  private val identityTableName = "identity_tbl"
  private val keyTableName = "key_tbl"
  private val recoveryIdentitiesTableName = "recovery_tbl"


  import sss.asado.util.ByteArrayEncodedStrOps._

  def apply(maxKeysPerIdentity: Int = 10, maxRescuersPerIdentity: Int = 5)(implicit db:Db): IdentityService = new IdentityService {

    private val createIdentityTableSql =
      s"""CREATE TABLE IF NOT EXISTS $identityTableName
          |($id BIGINT GENERATED BY DEFAULT AS IDENTITY (START WITH 1, INCREMENT BY 1) UNIQUE,
          |$identityCol VARCHAR(100),
          |$createdCol BIGINT,
          |PRIMARY KEY($identityCol));
          |""".stripMargin

    private val createKeyTableSql =
      s"""CREATE TABLE IF NOT EXISTS $keyTableName
          |($id BIGINT GENERATED BY DEFAULT AS IDENTITY (START WITH 1, INCREMENT BY 1),
          |$identityLnkCol BIGINT,
          |$publicKeyCol VARCHAR(100),
          |$tagCol VARCHAR(50),
          |$createdCol BIGINT,
          |FOREIGN KEY ($identityLnkCol) REFERENCES $identityTableName($id),
          |PRIMARY KEY($identityLnkCol, $publicKeyCol));
          |""".stripMargin

    private val createRecoveryTableSql =
      s"""CREATE TABLE IF NOT EXISTS $recoveryIdentitiesTableName
          |($id BIGINT GENERATED BY DEFAULT AS IDENTITY (START WITH 1, INCREMENT BY 1),
          |$identityLnkCol BIGINT,
          |$identityCol VARCHAR(100),
          |$domainCol VARCHAR(100) DEFAULT NULL,
          |$createdCol BIGINT,
          |FOREIGN KEY ($identityLnkCol) REFERENCES $identityTableName($id),
          |PRIMARY KEY($identityLnkCol, $identityCol, $domainCol));
          |""".stripMargin

    db.executeSqls(Seq(createIdentityTableSql, createKeyTableSql, createRecoveryTableSql))


    private lazy val identityTable = db.table(identityTableName)
    private lazy val keyTable = db.table(keyTableName)
    private lazy val recoveryTable = db.table(recoveryIdentitiesTableName)

    private def toIdOpt(identity: String): Option[Long] = identityTable.toLongIdOpt(s"$identityCol" -> identity)

    private def toId(identity: String): Long = toIdOpt(identity).get

    private def usingIdentity[T](identity: String)(f: Long => T): T = {
      toIdOpt(identity) match {
        case None => throw new IllegalArgumentException(s"No such identity $identity")
        case Some(rowId) => f(rowId)
      }
    }

    override def matches(identity: String, publicKey: PublicKey): Boolean = {
      toIdOpt(identity) match {
        case None => false
        case Some(rowId) =>
          val asChars = publicKey.toBase64Str
          keyTable.find(Where(s"$identityLnkCol = ? AND $publicKeyCol = ? ", rowId, asChars)).isDefined
      }
    }

    override def unlink(identity: String, tag: String): Boolean = {
      usingIdentity(identity) { id =>
        val result = keyTable.delete(where(s"$identityLnkCol = ? AND $tagCol = ? ", id, tag)) == 1
        freeIdentityIfNoKeysOrRescuers(identity)
        result
      }
    }

    def rescuers(identity: String): Seq[String] = {
      val fk = identityTable.toLongId(identityCol -> identity)
      recoveryTable.filter(identityLnkCol -> fk).map(_[String](identityCol))
    }

    private def freeIdentityIfNoKeysOrRescuers(identity: String): Unit = {
      if(accounts(identity).isEmpty &&
        rescuers(identity).isEmpty) {
        identityTable.delete(where(s"$identityCol = ?") using identity)
        // remove this identity from being a rescuer, otherwise a reclaimed identity
        // could be a valid rescuer for an identity
        recoveryTable.delete(where(s"$identityCol = ?") using identity)

      }

    }

    override def unlink(identity: String, publicKey: PublicKey): Boolean = {
      usingIdentity(identity) { identityId =>
        val asChars = publicKey.toBase64Str
        val result = keyTable.delete(where(s"$identityLnkCol = ? AND $publicKeyCol = ? ", identityId, asChars)) == 1
        freeIdentityIfNoKeysOrRescuers(identity)
        result
      }
    }

    override def link(identity: String, publicKey: PublicKey, tag: String): Unit = {
      require(accounts(identity).size <= maxKeysPerIdentity, s"No more than $maxKeysPerIdentity keys allowed per identity.")
      usingIdentity(identity) { id =>
          val asChars = publicKey.toBase64Str
          keyTable.insert(Map(identityLnkCol -> id,
            publicKeyCol -> asChars,
            tagCol -> tag,
            createdCol -> DateTime.now.getMillis))
      }
    }

    override def accountOpt(identity: String, tag: String): Option[PublicKeyAccount] = toIdOpt(identity) flatMap { id =>
      keyTable.find(where(s"$identityLnkCol = ? AND $tagCol = ?") using (id, tag)) map{
        row => PublicKeyAccount(row[String](publicKeyCol).toByteArray)
      }
    }

    override def account(identity: String, tag: String): PublicKeyAccount = accountOpt(identity, tag).get

    override def accounts(identity: String): Seq[TaggedPublicKeyAccount] = {
      toIdOpt(identity) match {
        case None => Seq()
        case Some(rowId) => keyTable.filter(where(s"$identityLnkCol = ?") using rowId) map { r: Row =>
            TaggedPublicKeyAccount(PublicKeyAccount(r(publicKeyCol).toByteArray), r(tagCol))
          }
      }
    }

    override def identify(publicKey: PublicKey): Option[TaggedIdentity] = {
      val pKey = publicKey.toBase64Str
      keyTable.find(where (s"$publicKeyCol = ?") using pKey) map { r =>
        val linkId = r[Long](identityLnkCol)
        val id = identityTable(linkId)[String](identityCol)
        val tag = r[String](tagCol)
        TaggedIdentity(id, tag)
      }
    }

    override def claim(identity: String, publicKey: PublicKey, tag: String) = db.tx {
      Try {
        val newRow = identityTable.insert(Map(identityCol -> identity, createdCol -> new Date().getTime))
        keyTable.insert(Map(identityLnkCol -> newRow[Long](id), publicKeyCol -> publicKey.toBase64Str,
          tagCol -> tag, createdCol -> new Date().getTime))
      } match {
        case Failure(e: SQLIntegrityConstraintViolationException) =>
          throw new IllegalArgumentException(s"Identifier $identity already taken")

        case Failure(e) => throw new IllegalArgumentException(s"Failed to claim identity $identity", e)
        case Success(_) =>
      }
    }

    override def linkRescuer(identity: String, rescuer: String): Unit = {
      require(accountOpt(rescuer).isDefined, s"Rescuer $rescuer does not exist!")
      require(rescuers(identity).size <= maxRescuersPerIdentity, s"No more than $maxRescuersPerIdentity rescuers allowed per identity.")
      if(!rescuers(identity).contains(rescuer)) {
        toIdOpt(identity) match {
          case None => throw new IllegalArgumentException(s"No such identity $identity")
          case Some(rowId) => recoveryTable.persist(Map(identityLnkCol -> rowId,
            identityCol -> rescuer,
            domainCol -> "",
            createdCol -> new Date().getTime))
        }
      }
    }

    override def unLinkRescuer(identity: String, rescuer: String): Unit = {
      require(accountOpt(rescuer).isDefined, s"Rescuer $rescuer does not exist!")
      require(rescuers(identity).contains(rescuer), s"$rescuer is not in the rescue list of $identity")
      toIdOpt(identity) match {
        case None => throw new IllegalArgumentException(s"No such identity $identity")
        case Some(rowId) => recoveryTable.delete(where(s"$identityLnkCol = ? AND $identityCol = ?") using (rowId, rescuer))
      }
     }
  }
}

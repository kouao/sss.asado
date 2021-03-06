package sss.asado.block.merkle

import sss.ancillary.Logging
import sss.asado.util.hash.FastCryptographicHash
import sss.db.{Db, Where}

import scala.annotation.tailrec
import scala.collection.mutable

/**
  * Copyright Stepping Stone Software Ltd. 2016, all rights reserved. 
  * mcsherrylabs on 3/16/16.
  */
object MerklePersist {

  private val merkleTableNamePrefix = "merkle_"
  def tableName(height: Long) = s"$merkleTableNamePrefix$height"

  implicit def hash(a:mutable.WrappedArray[Byte], b: mutable.WrappedArray[Byte]): mutable.WrappedArray[Byte] =
    FastCryptographicHash.hash(a.array) ++ FastCryptographicHash.hash(b.array)

  def path(tag: String, leaf: Array[Byte])(implicit db: Db):Option[Seq[Array[Byte]]] = {

    val t = db.table(tag)

    t.find(Where("hash = ?", leaf)) map { r =>

      @tailrec
      def getParentHash(acc: Seq[Array[Byte]], parentIdOpt: Option[Int]): Seq[Array[Byte]] = parentIdOpt match {
        case Some(pId) => t.get(pId) match  {
          case Some(row) => {
            val hash: Array[Byte] = row[Array[Byte]]("hash")
            val nextParentId = Option(row.get("parentId")).map(_.asInstanceOf[Int])
            getParentHash(hash +: acc, nextParentId)
          }
          case None => throw new Error(s"Impossible to not read parentId $pId")
        }
        case None => acc
      }
      val parentIdOpt = Option(r.get("parentId")).map(_.asInstanceOf[Int])
      getParentHash(Seq(), parentIdOpt)

    }


  }

  implicit class MerklePersister(mt: MerkleTree[mutable.WrappedArray[Byte]])(implicit db: Db) extends Logging {

    private def createTableSql(name: String) =
      s"""CREATE TABLE IF NOT EXISTS $name
         |(id INT GENERATED BY DEFAULT AS IDENTITY (START WITH 1, INCREMENT BY 1),
         |hash BLOB,
         |parentId INT);
         |""".stripMargin

    private def createIndexSql(name: String, col: String) = s"CREATE INDEX IF NOT EXISTS ${name}_${col}_indx ON $name ($col);"

    def persist(tag: String): Unit = {

      db.inTransaction {
        db.executeSqls(Seq(createTableSql(tag), createIndexSql(tag, "id"), createIndexSql(tag, "parentId")))
        val table = db.table(tag)
        persist(mt, None)

        def persist(subTree: MerkleTree[_], parentIdOpt: Option[Int]): Unit = {

          val nextParentId = Option(table.insert(Map("hash" -> subTree.root, "parentId" -> parentIdOpt))[Int]("id"))

          subTree match {
            case mtb: MerkleTreeBranch[_] => {
              persist(mtb.left, nextParentId)
              if (mtb.left != mtb.right) persist(mtb.right, nextParentId)
            }
            case MerkleTreeLeaf(left: mutable.WrappedArray[_], right: mutable.WrappedArray[_]) => {
              table.insert(Map("hash" -> left, "parentId" -> nextParentId))
              if (left != right) table.insert(Map("hash" -> right, "parentId" -> nextParentId))
            }

          }
        }

      }
    }
  }
}

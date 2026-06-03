package repositories

import javax.inject._
import io.getquill._
import models.PerkEntity
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class PerkRepository @Inject()(implicit ec: ExecutionContext) {

  // Initialize Quill Context explicitly for MySQL
  // SnakeCase matching means db columns like 'deal_type' map automatically to Scala 'dealType'
  val ctx = new MysqlJdbcContext(SnakeCase, "db")
  import ctx._

  // Fetch a perk by ID from MySQL
  def getById(id: Long): Future[Option[PerkEntity]] = Future {
    ctx.run(query[PerkEntity].filter(_.id == lift(id))).headOption
  }

  // Fetch all perks for initial batch sync indexing
  def getAllPerks: Future[List[PerkEntity]] = Future {
    ctx.run(query[PerkEntity])
  }
}
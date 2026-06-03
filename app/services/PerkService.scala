package services


import javax.inject._
import repositories.PerkRepository
import models.{PerkDocument, PerkEntity, TypesenseResponse}
import org.apache.pekko.protobufv3.internal.Struct
import play.api.libs.ws._

import scala.concurrent.{ExecutionContext, Future}


@Singleton
class PerkService @Inject()(
                             dbRepo: PerkRepository,
                             searchClient: TypesenseClient
                           )(implicit ec: ExecutionContext) {

  def initializeSearchSchema(): Future[WSResponse] = {
    searchClient.createCollection()
  }
  // Fetch from db and push into typesense index
  def syncPerkToSearchIndex(perkId: Long): Future[Boolean] = {
    dbRepo.getById(perkId).flatMap {
      case Some(perkEntity) =>
        val doc = PerkDocument.fromEntity(perkEntity)
        searchClient.indexDocument(doc).map(_.status ==200)

      case None => Future.successful(false)
    }
  }

  // Route searches straight to Typesense client
  def searchPerks(query: String, dealType: Option[String]):Future[TypesenseResponse] = {
    val filterStr = dealType.map(t => s"dealType:=$t")
    searchClient.search(query,filterStr)
  }
}

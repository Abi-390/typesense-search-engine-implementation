package controllers

import javax.inject._
import play.api.mvc._
import play.api.libs.json.Json
import services._
import scala.concurrent.ExecutionContext

@Singleton
class SearchController @Inject()(
                                  cc: ControllerComponents,
                                  perkService: PerkService,
                                  typesenseClient: TypesenseClient
                                )(implicit ec: ExecutionContext) extends AbstractController(cc) {

        // GET /v1/search?q=banana&dealType=onlinedeal
  def search(q:String, dealType:Option[String]) = Action.async{ implicit request =>
    perkService.searchPerks(q,dealType).map{tsResponse =>
      Ok(Json.toJson(tsResponse))
    }.recover{
      case ex: Exception => InternalServerError(Json.obj("error"->ex.getMessage))
    }
  }

  // POST /v1/sync/:id
  def sync(id:Long) = Action.async  { implicit  request=>
    perkService.syncPerkToSearchIndex(id).map{
      case true => Ok(Json.obj("status"->"Synced successfully"))
      case false => NotFound(Json.obj("Status"->"Perk not found in DB"))
    }
  }

  // Add this inside SearchController.scala
  def initCollection = Action.async { implicit request =>
    perkService.initializeSearchSchema().map { response =>
      if (response.status == 201 || response.status == 200) {
        Ok(Json.obj("status" -> "Collection 'perks' created successfully!"))
      } else {
        BadRequest(Json.obj("error" -> s"Failed to create collection: ${response.body}"))
      }
    }
  }



}

package services

import javax.inject._
import play.api.Configuration
import play.api.libs.ws._
import play.api.libs.json._
import models.{PerkDocument,TypesenseResponse}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class TypesenseClient @Inject()(ws:WSClient,config:Configuration)(implicit ec:ExecutionContext) {

  private val tsProtocol = config.get[String]("typesense.protocol")
  private val tsHost = config.get[String]("typesense.host")
  private val tsPort = config.get[Int]("typesense.port")
  private val apiKey = config.get[String]("typesense.apiKey")

  private val baseUrl = s"$tsProtocol://$tsHost:$tsPort"

  // Helper for authorized headers
  private def client(urlPath: String):WSRequest = {
    ws.url(s"$baseUrl$urlPath")
      .withHttpHeaders(
        "X-TYPESENSE-API-KEY" -> apiKey,
        "Content-Type" -> "application/json"
      )
  }

  // Create collection scheme ( equivalent to configuring an Algolia Index )
  def createCollection():Future[WSResponse] = {
    val schema = Json.obj(
        "name" -> "perks",
        "fields" -> Json.arr(
          Json.obj("name"->"id", "type"->"string"),
          Json.obj("name"->"name","type"->"string"),
          Json.obj("name"->"dealType","type"->"string","facet"->true),
          Json.obj("name"->"companyName","type"->"string","facet"-> true)
        )
    )
    client("/collections").post(schema)
  }

  // Index / Upsert a document
  def indexDocument(doc: PerkDocument): Future[WSResponse] = {
    client(s"/collections/perks/documents?action=upsert")
      .post(Json.toJson(doc))
  }

  // 3. Search documents (Multi-index logic can be simulated with separate parameters)
  def search(query:String, filterBy:Option[String]=None):Future[TypesenseResponse] = {
    var req = client("/collections/perks/documents/search")
      .withQueryStringParameters(
        "q"->query,
        "queryBy"->"name,companyName"
      )

    // Append filer criteria dynamically if passed( e.g., dealType:=onlinedeal)
    filterBy.foreach{filter =>
    req = req.withQueryStringParameters("filterBy"-> filter)
    }

    req.get().map{response =>{
      if(response.status == 200){
        response.json.as[TypesenseResponse]
      } else{
        throw new RuntimeException(s"Typesense search failed: ${response.body}")
      }
    }}


  }


}

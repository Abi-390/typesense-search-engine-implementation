package models

import play.api.libs.json._

// DB entity for quill
case class PerkEntity(
                     id: Long,
                     name: String,
                     dealType: String,
                     companyName: String
                     )

// Typesense document model ( typesense requires id to be string )
case class PerkDocument(
                       id: String,
                       name: String,
                       dealType: String,
                       companyName: String
                       )

object PerkDocument {
  implicit val format: OFormat[PerkDocument] = Json.format[PerkDocument]

  // helper to convert db entity to search document
  def fromEntity(entity:PerkEntity):PerkDocument = {
    PerkDocument(entity.id.toString, entity.name, entity.dealType, entity.companyName)
  }
}

// Typesense search result structures
case class TypesenseHit(document: PerkDocument)
object TypesenseHit {
  implicit val format: OFormat[TypesenseHit] = Json.format[TypesenseHit]
}

case class  TypesenseResponse(hits: List[TypesenseHit],found:Int)
object TypesenseResponse {
  implicit val format:OFormat[TypesenseResponse] = Json.format[TypesenseResponse]
}
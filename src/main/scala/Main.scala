import scala.concurrent.ExecutionContextExecutor
import scala.io.StdIn
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Directives._
import caliban.interop.circe.AkkaHttpCirceAdapter
import zio.clock.Clock
import zio.console.Console
import zio.internal.Platform
import zio.Runtime

import caliban.GraphQL.graphQL
import caliban.RootResolver

object Data {
  case class RawAuthor(
      id: String,
      name: String,
      bookIds: List[String]
  )

  case class RawBook(id: String, title: String, publishYear: Int)

  val authors = List(
    RawAuthor("0", "Amirali", "0" :: Nil),
    RawAuthor("1", "Kyle simpson", "2" :: Nil),
    RawAuthor("2", "Navid", "1" :: Nil)
  )

  val books = List(
    RawBook("0", "I like oop", 2020),
    RawBook("1", "Employee handbook", 2018),
    RawBook("2", "You don't know js", 2020)
  )
}

object Main extends App with AkkaHttpCirceAdapter {
  case class Book(id: String, title: String, publishYear: Int)
  case class Author(id: String, name: String, books: () => List[Book])

  case class AuthorByNameArgs(name: String)

  def booksByIds(ids: List[String]) =
    Data.books
      .filter(b => ids.contains(b.id))
      .map(b => Book(b.id, b.title, b.publishYear))

  def authorByName(name: String) =
    Data.authors
      .find(_.name == name)
      .map(a => Author(a.id, a.name, () => booksByIds(a.bookIds)))

  def allAuthors =
    Data.authors.map(a => Author(a.id, a.name, () => booksByIds(a.bookIds)))

  def allBooks = Data.books.map(b => Book(b.id, b.title, b.publishYear))

  case class Queries(
      authorByName: AuthorByNameArgs => Option[Author],
      allAuthors: () => List[Author],
      allBooks: () => List[Book]
  )

  val queries =
    Queries(args => authorByName(args.name), () => allAuthors, () => allBooks)

  val api = graphQL(RootResolver(queries))

  implicit val system: ActorSystem = ActorSystem()
  implicit val executionContext: ExecutionContextExecutor = system.dispatcher
  implicit val runtime: Runtime[Console with Clock] =
    Runtime.unsafeFromLayer(
      Console.live ++ Clock.live,
      Platform.default
    )

  val interpreter =
    runtime.unsafeRun(api.interpreter)

  val route =
    path("api" / "graphql") {
      adapter.makeHttpService(interpreter)
    } ~ path("ws" / "graphql") {
      adapter.makeWebSocketService(interpreter)
    } ~ path("graphiql") {
      getFromResource("graphiql.html")
    }

  val bindingFuture = Http().bindAndHandle(route, "0.0.0.0", 8080)

  StdIn.readLine()
  bindingFuture
    .flatMap(_.unbind())
    .onComplete(_ => system.terminate())
}

package moe.pizza.auth.webapp

import javax.servlet.http.HttpSession

import moe.pizza.auth.adapters.{GraderChain, GraderChainSpec}
import moe.pizza.auth.interfaces.{PilotGrader, UserDatabase}
import moe.pizza.auth.models.Pilot
import moe.pizza.auth.models.Pilot.Status
import moe.pizza.auth.webapp.Types.Alert
import moe.pizza.auth.webapp.WebappTestSupports._
import moe.pizza.crestapi.CrestApi
import moe.pizza.crestapi.CrestApi.{VerifyResponse, CallbackResponse}
import moe.pizza.eveapi.{XMLApiResponse, EVEAPI}
import moe.pizza.eveapi.generated.eve.CharacterInfo.{Result, Eveapi, Rowset}
import org.joda.time.DateTime
import org.scalatest.mock.MockitoSugar
import org.scalatest.{FlatSpec, MustMatchers}
import org.mockito.Mockito.{when, verify, never, reset, times, spy}
import org.mockito.Matchers.{anyString, anyInt}
import spark._

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.Try

/**
  * Created by Andi on 19/02/2016.
  */
class WebappSpec extends FlatSpec with MustMatchers with MockitoSugar {

  val ACCEPTHTML = "text/html"

  "Webapp" should "serve the landing page" in {
    withPort { port =>
      val w = new Webapp(readTestConfig(), new GraderChain(Seq()), port, null)
      w.start()
      val handler = resolve(spark.route.HttpMethod.get, "/", ACCEPTHTML)
      val req = mock[Request]
      val session = mock[Session]
      when(req.session()).thenReturn(session)
      when(session.attribute(Webapp.SESSION)).thenReturn(null)
      val resp = mock[Response]
      val res = handler.handle[String](req, resp)
      res.trim must equal(templates.html.base.apply("pizza-auth-3", templates.html.landing.apply(), None, None).toString().trim)
      val posthandler = resolve(spark.route.HttpMethod.after, "/", ACCEPTHTML)
      val res2 = posthandler.filter[Any](req, resp)
      verify(session, times(2)).attribute[Types.Session](Webapp.SESSION)
      Spark.stop()
    }
  }

  "Webapp" should "serve the main page" in {
    withPort { port =>
      val w = new Webapp(readTestConfig(), new GraderChain(Seq()), port, null)
      w.start()
      val handler = resolve(spark.route.HttpMethod.get, "/", ACCEPTHTML)
      val req = mock[Request]
      val session = mock[Session]
      val usersession = new Types.Session(List.empty[Types.Alert])
      when(req.session()).thenReturn(session)
      when(session.attribute(Webapp.SESSION)).thenReturn(usersession)
      val resp = mock[Response]
      val res = handler.handle[String](req, resp)
      res.trim must equal(templates.html.base.apply("pizza-auth-3", templates.html.main.apply(), Some(usersession), None).toString().trim)
      Spark.stop()
    }
  }

  "Webapp" should "serve the main page with alerts" in {
    withPort { port =>
      val w = new Webapp(readTestConfig(), new GraderChain(Seq()), port, null)
      w.start()
      val handler = resolve(spark.route.HttpMethod.get, "/", ACCEPTHTML)
      val req = mock[Request]
      val session = mock[Session]
      val alert = Types.Alert("info", "ducks are cool too")
      val usersession = new Types.Session(List(alert))
      when(req.session()).thenReturn(session)
      when(session.attribute(Webapp.SESSION)).thenReturn(usersession)
      val resp = mock[Response]
      val res = handler.handle[String](req, resp)
      res.trim must equal(templates.html.base.apply("pizza-auth-3", templates.html.main.apply(), Some(usersession), None).toString().trim)
      // ensure that our alert got shown
      res contains "ducks are cool too" must equal(true)
      // run the post-filter
      val posthandler = resolve(spark.route.HttpMethod.after, "/", ACCEPTHTML)
      val res2 = posthandler.filter[Any](req, resp)
      // make sure it cleared the alerts
      verify(session).attribute(Webapp.SESSION, usersession.copy(alerts = List.empty[Types.Alert]))
      Spark.stop()
    }
  }

  "Webapp" should "redirect to CREST on /login" in {
    withPort { port =>
      val w = new Webapp(readTestConfig(), new GraderChain(Seq()), port, null)
      w.start()
      val handler = resolve(spark.route.HttpMethod.get, "/login", ACCEPTHTML)
      val req = mock[Request]
      val session = mock[Session]
      when(req.session()).thenReturn(session)
      when(session.attribute(Webapp.SESSION)).thenReturn(null)
      val resp = mock[Response]
      val res = handler.handle[String](req, resp)
      verify(resp).redirect("https://sisilogin.testeveonline.com/oauth/authorize/?response_type=code&redirect_uri=http://localhost:4567/callback&client_id=f&scope=characterLocationRead&state=login")
      Spark.stop()
    }
  }

  "Webapp" should "redirect to CREST on /signup" in {
    withPort { port =>
      val w = new Webapp(readTestConfig(), new GraderChain(Seq()), port, null)
      w.start()
      val handler = resolve(spark.route.HttpMethod.get, "/signup", ACCEPTHTML)
      val req = mock[Request]
      val session = mock[Session]
      when(req.session()).thenReturn(session)
      when(session.attribute(Webapp.SESSION)).thenReturn(null)
      val resp = mock[Response]
      val res = handler.handle[String](req, resp)
      verify(resp).redirect("https://sisilogin.testeveonline.com/oauth/authorize/?response_type=code&redirect_uri=http://localhost:4567/callback&client_id=f&scope=characterLocationRead&state=signup")
      Spark.stop()
    }
  }

  "Webapp" should "clear the session on /logout" in {
    withPort { port =>
      val w = new Webapp(readTestConfig(), new GraderChain(Seq()), port, null)
      w.start()
      val handler = resolve(spark.route.HttpMethod.get, "/logout", ACCEPTHTML)
      val req = mock[Request]
      val httpsession = mock[HttpSession]
      val session = reflectSession(httpsession)
      when(req.session).thenReturn(session)
      when(session.attribute(Webapp.SESSION)).thenReturn(null)
      val resp = mock[Response]
      val res = handler.handle[String](req, resp)
      verify(req).session()
      verify(resp).redirect("/")
      verify(httpsession).invalidate()
      Spark.stop()
    }
  }


  "Webapp" should "verify crest callbacks when doing a login" in {
    withPort { port =>
      val crest = mock[CrestApi]
      val w = new Webapp(readTestConfig(), new GraderChain(Seq()), port, null, Some(crest))
      w.start()
      val handler = resolve(spark.route.HttpMethod.get, "/callback", ACCEPTHTML)
      val req = mock[Request]
      val session = mock[Session]
      when(req.session).thenReturn(session)
      val resp = mock[Response]
      // arguments
      when(req.queryParams("code")).thenReturn("CRESTCODE")
      when(req.queryParams("state")).thenReturn("login")
      when(crest.callback("CRESTCODE")).thenReturn(Future{new CallbackResponse("ACCESSTOKEN", "TYPE", 100, Some("REF"))})
      when(crest.verify("ACCESSTOKEN")).thenReturn(Future{new VerifyResponse(1, "Bob", "ages", "scopes", "bearer", "owner", "eve")})
      val res = handler.handle[String](req, resp)
      verify(req).queryParams("code")
      verify(req).queryParams("state")
      verify(crest).callback("CRESTCODE")
      verify(crest).verify("ACCESSTOKEN")
      val finalsession = new Types.Session(List(new Alert("success", "Thanks for logging in %s".format("Bob"))))
      verify(session).attribute(Webapp.SESSION, finalsession)
      Spark.stop()
    }
  }

  "Webapp" should "look up the pilot in crest and the XML API, create a pilot, then redirect to /signup/confirm" in {
    withPort { port =>
      val crest = mock[CrestApi]
      val ud = mock[UserDatabase]
      val eveapi = mock[EVEAPI]
      val eve = mock[moe.pizza.eveapi.endpoints.Eve]
      when(eveapi.eve).thenReturn(eve)
      val w = new Webapp(readTestConfig(), new GraderChain(Seq()), port, null, Some(crest), Some(eveapi))
      w.start()
      val handler = resolve(spark.route.HttpMethod.get, "/callback", ACCEPTHTML)
      val req = mock[Request]
      val session = mock[Session]
      when(req.session).thenReturn(session)
      val resp = mock[Response]
      // arguments
      when(req.queryParams("code")).thenReturn("CRESTCODE")
      when(req.queryParams("state")).thenReturn("signup")
      when(crest.callback("CRESTCODE")).thenReturn(Future{new CallbackResponse("ACCESSTOKEN", "TYPE", 100, Some("REF"))})
      when(crest.verify("ACCESSTOKEN")).thenReturn(Future{new VerifyResponse(1, "Bob", "ages", "scopes", "bearer", "owner", "eve")})
      when(crest.refresh("REF")).thenReturn(Future{new CallbackResponse("ACCESSTOKEN", "TYPE", 100, Some("REF"))})
      val bob = new Eveapi("now", new Result(1, "Bob", "bobrace", 0, "bobbloodline", 0, "bobancestry", 42, "bobcorp", "some date", 42, "boballiance", "some date", 0.0, Rowset()), "whenever")
      when(eve.CharacterInfo(1)).thenReturn(Future{Try{new XMLApiResponse[Result](DateTime.now(), DateTime.now(), bob.result)}})
      val res = handler.handle[String](req, resp)
      verify(req).queryParams("code")
      verify(req).queryParams("state")
      verify(crest).callback("CRESTCODE")
      verify(crest).verify("ACCESSTOKEN")
      verify(resp).redirect("/signup/confirm")
      Spark.stop()
    }
  }

  "Webapp" should "show a confirmation page when confirming a signup with a pilot" in {
    withPort { port =>
      val crest = mock[CrestApi]
      val ud = mock[UserDatabase]
      val eveapi = mock[EVEAPI]
      val eve = mock[moe.pizza.eveapi.endpoints.Eve]
      when(eveapi.eve).thenReturn(eve)
      val w = new Webapp(readTestConfig(), new GraderChain(Seq()), port, null, Some(crest), Some(eveapi))
      w.start()
      val handler = resolve(spark.route.HttpMethod.get, "/signup/confirm", ACCEPTHTML)
      val req = mock[Request]
      val session = mock[Session]
      val p = Pilot("bob", Pilot.Status.internal, "myalliance", "mycorp", "Bob", "bob@bob.com", Pilot.OM.readTree("{\"meta\": \"%s\"}".format("metafield")), List("group1", "group3"), List("123:bobkey"), List.empty )
      when(req.session).thenReturn(session)
      when(session.attribute[Pilot](Webapp.PILOT)).thenReturn(p)
      val resp = mock[Response]
      // arguments
      val res = handler.handle[play.twirl.api.Html](req, resp)
      verify(session).attribute[Pilot](Webapp.PILOT)
      res must equal(templates.html.base("pizza-auth-3", templates.html.signup(p), None, Some(p)))
      Spark.stop()
    }
  }

  "Webapp" should "redirect the user back to the index if they didn't have a stored pilot on the signup page" in {
    withPort { port =>
      val crest = mock[CrestApi]
      val ud = mock[UserDatabase]
      val eveapi = mock[EVEAPI]
      val eve = mock[moe.pizza.eveapi.endpoints.Eve]
      when(eveapi.eve).thenReturn(eve)
      val w = new Webapp(readTestConfig(), new GraderChain(Seq()), port, null, Some(crest), Some(eveapi))
      w.start()
      val handler = resolve(spark.route.HttpMethod.get, "/signup/confirm", ACCEPTHTML)
      val req = mock[Request]
      val session = mock[Session]
      when(req.session).thenReturn(session)
      when(session.attribute[Pilot](Webapp.PILOT)).thenReturn(null)
      val resp = mock[Response]
      // arguments
      val res = handler.handle[AnyRef](req, resp)
      verify(session).attribute[Pilot](Webapp.PILOT)
      verify(resp).redirect("/")
      Spark.stop()
    }
  }

}

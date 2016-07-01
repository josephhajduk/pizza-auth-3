package moe.pizza.auth.graphdb

import java.io.{BufferedReader, File, InputStreamReader}
import java.util

import com.github.tototoshi.csv._
import com.orientechnologies.orient.core.db.document.ODatabaseDocumentTx
import com.orientechnologies.orient.core.metadata.schema.OType
import com.orientechnologies.orient.core.record.impl.ODocument
import com.orientechnologies.orient.core.sql.query.OSQLSynchQuery
import com.tinkerpop.blueprints.impls.orient.{OrientGraph, OrientGraphFactory, OrientGraphNoTx, OrientVertex}
import org.apache.commons.io.FileUtils

import scala.collection.JavaConverters._
import scalaz._, Scalaz._


class EveMapDb(dbname: String = "map") {

  val graphfactory = new OrientGraphFactory(s"plocal:$dbname")

  def withGraph[T](f: (OrientGraph => T)): T = {
    val t = graphfactory.getTx
    val r = f(t)
    t.commit()
    t.shutdown()
    r
  }

  def withGraphNoTx[T](f: (OrientGraphNoTx => T)): T = {
    val t = graphfactory.getNoTx
    val r = f(t)
    t.commit()
    t.shutdown()
    r
  }

  def provisionIfRequired() = {
    var provision: Boolean = false
    withGraph { graph =>
      // if the database doesn't look provisioned, provision it
      if (graph.getEdgeType("gate") == null)
        provision = true
    }
    if (provision) {
      initialize()
    }
  }


  def initialize() {
    val systems = CSVReader.open(
      new BufferedReader(
        new InputStreamReader(
          getClass().getResourceAsStream("/databases/systems.csv")))).allWithHeaders()
    val jumps = CSVReader.open(
      new BufferedReader(
        new InputStreamReader(getClass().getResourceAsStream("/databases/jumps.csv")))).allWithHeaders()

    withGraphNoTx { graph =>
      try {
        val solarsystem = graph.createVertexType("solarsystem")
        solarsystem.createProperty("solarSystemName", OType.STRING)
        solarsystem.createProperty("solarSystemID", OType.INTEGER)
        solarsystem.createProperty("solarSystemX", OType.DOUBLE)
        solarsystem.createProperty("solarSystemY", OType.DOUBLE)
        solarsystem.createProperty("solarSystemZ", OType.DOUBLE)

        graph.setUseLightweightEdges(true)
        val gate = graph.createEdgeType("gate")
      } finally {
        graph.rollback()
      }
    }

    val lookup = new util.HashMap[Int, OrientVertex]()

    withGraph { graph =>
      for (system <- systems) {
        val s = graph.addVertex("class:solarsystem", Seq(): _*)
        s.setProperty("solarSystemName", system("solarSystemName"))
        s.setProperty("solarSystemID", system("solarSystemID"))

        s.setProperty("solarSystemX",system("x").toDouble)
        s.setProperty("solarSystemY",system("y").toDouble)
        s.setProperty("solarSystemZ",system("z").toDouble)

        lookup.put(system("solarSystemID").toInt, s)
      }
    }


    for (gates <- jumps.grouped(100)) {
      withGraph { graph =>
        for (gate <- gates) {
          val from = gate("fromSolarSystemID").toInt
          val to = gate("toSolarSystemID").toInt
          val f = lookup.get(from)
          val t = lookup.get(to)
          val e = graph.addEdge("class:gate", f, t, "gate")
        }
      }
    }
  }

  implicit class dbWrapper(db: ODatabaseDocumentTx) {
    def queryBySql[T](sql: String, params: AnyRef*): List[T] = {
      val params4java = params.toArray
      val results: java.util.List[T] = db.query(new OSQLSynchQuery[T](sql), params4java: _*)
      results.asScala.toList
    }
  }

  def euclidDistance(x1:(Double,Double,Double),x2:(Double,Double,Double)) = {
    val a = (x1._1-x2._1)*(x1._1-x2._1)
    val b = (x1._2-x2._2)*(x1._2-x2._2)
    val c = (x1._3-x2._3)*(x1._3-x2._3)
    math.sqrt(a+b+c)
  }

  val onely = 9460528450000000f

  def getSystemByName(graph: OrientGraph)(systemName:String) = graph.getVertices("solarSystemName", systemName).iterator().asScala.toList.headOption
  def getSystemById(graph: OrientGraph)(systemId:Int) = graph.getVertices("solarSystemID", systemId).iterator().asScala.toList.headOption

  def getLyDistance(s1:String, s2:String):Option[Double] = {
    if (s1==s2) {
      return Some(0f)
    }
    withGraph { graph =>
      val s1p = getSystemByName(graph)(s1).map{s =>
        (s.getProperty[Double]("solarSystemX"),s.getProperty[Double]("solarSystemY"),s.getProperty[Double]("solarSystemZ"))}
      val s2p = getSystemByName(graph)(s2).map{s =>
        (s.getProperty[Double]("solarSystemX"),s.getProperty[Double]("solarSystemY"),s.getProperty[Double]("solarSystemZ"))}

      Zip[Option].zip(s1p,s2p).map(k=>euclidDistance(k._1,k._2)/onely)
    }
  }

  def getLyDistance(s1:String, s2:Int):Option[Double] = {
    if (s1==s2) {
      return Some(0f)
    }
    withGraph { graph =>
      val s1p = getSystemByName(graph)(s1).map{s =>
        (s.getProperty[Double]("solarSystemX"),s.getProperty[Double]("solarSystemY"),s.getProperty[Double]("solarSystemZ"))}
      val s2p = getSystemById(graph)(s2).map{s =>
        (s.getProperty[Double]("solarSystemX"),s.getProperty[Double]("solarSystemY"),s.getProperty[Double]("solarSystemZ"))}

      Zip[Option].zip(s1p,s2p).map(k=>euclidDistance(k._1,k._2)/onely)
    }
  }

  def getLyDistance(s1:Int, s2:Int):Option[Double] = {
    if (s1==s2) {
      return Some(0f)
    }
    withGraph { graph =>
      val s1p = getSystemById(graph)(s1).map{s =>
        (s.getProperty[Double]("solarSystemX"),s.getProperty[Double]("solarSystemY"),s.getProperty[Double]("solarSystemZ"))}
      val s2p = getSystemById(graph)(s2).map{s =>
        (s.getProperty[Double]("solarSystemX"),s.getProperty[Double]("solarSystemY"),s.getProperty[Double]("solarSystemZ"))}

      Zip[Option].zip(s1p,s2p).map(k=>euclidDistance(k._1,k._2)/onely)
    }
  }

  def getLyDistance(s1:Int, s2:String):Option[Double] = getLyDistance(s2,s1)

  def getDistanceBetweenSystemsByName(s1: String, s2: String): Option[Int] = {
    if (s1==s2) {
      return Some(0)
    }
    withGraph { graph =>
      val s1v = getSystemByName(graph)(s1).map{_.getId.toString}
      val s2v = getSystemByName(graph)(s2).map(_.getId.toString)
      if (s1v.isDefined && s2v.isDefined) {
        val result = graph.getRawGraph.queryBySql[ODocument](s"select flatten(shortestPath(${s1v.get}, ${s2v.get}, 'BOTH', 'gate'))")
        Some(result.size)
      } else {
        None
      }
    }
  }

  def getDistanceBetweenSystemsById(s1: Int, s2: Int): Option[Int] = {
    if (s1==s2) {
      return Some(0)
    }
    withGraph { graph =>
      val s1v = getSystemById(graph)(s1).map{_.getId.toString}
      val s2v = getSystemById(graph)(s2).map(_.getId.toString)
      if (s1v.isDefined && s2v.isDefined) {
        val result = graph.getRawGraph.queryBySql[ODocument](s"select flatten(shortestPath(${s1v.get}, ${s2v.get}, 'BOTH', 'gate'))")
        Some(result.size)
      } else {
        None
      }
    }
  }

  def getDistanceBetweenSystemsById(s1: String, s2: Int): Option[Int] = {
    if (s1==s2) {
      return Some(0)
    }
    withGraph { graph =>
      val s1v = getSystemByName(graph)(s1).map{_.getId.toString}
      val s2v = getSystemById(graph)(s2).map(_.getId.toString)
      if (s1v.isDefined && s2v.isDefined) {
        val result = graph.getRawGraph.queryBySql[ODocument](s"select flatten(shortestPath(${s1v.get}, ${s2v.get}, 'BOTH', 'gate'))")
        Some(result.size)
      } else {
        None
      }
    }
  }

  def getDistanceBetweenSystemsById(s1: Int, s2: String): Option[Int] = getDistanceBetweenSystemsById(s2,s1)

  def cleanUp() = {
    graphfactory.close()
    graphfactory.drop()
    FileUtils.deleteDirectory(new File(".", dbname))
  }

}

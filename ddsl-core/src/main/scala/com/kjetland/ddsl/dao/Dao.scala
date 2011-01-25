package com.kjetland.ddsl.dao

import org.joda.time.DateTime
import org.apache.log4j.Logger
import org.apache.zookeeper.{WatchedEvent, Watcher, CreateMode, ZooKeeper}
import org.apache.zookeeper.ZooDefs
import java.util.Properties
import org.joda.time.format.DateTimeFormat
import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import org.apache.commons.codec.net.URLCodec
import org.apache.zookeeper.data.Stat
import scala.collection.JavaConversions._
import com.kjetland.ddsl.model._

/**
 * Created by IntelliJ IDEA.
 * User: mortenkjetland
 * Date: 1/13/11
 * Time: 9:06 PM
 * To change this template use File | Settings | File Templates.
 */


object DdslDataConverter{

  val ddslDataVersion = "1.0"

  private val dtf = DateTimeFormat.forPattern("yyyyMMdd HH:mm:ss")

  def getServiceLocationAsString( sl : ServiceLocation) : String = {
    val props = new Properties()

    props.put("ddslDataVersion", ddslDataVersion)

    props.put( "url", sl.url)
    props.put( "testUrl", sl.testUrl)
    props.put( "quality", sl.quality.toString)
    props.put( "lastUpdated", dtf.print( sl.lastUpdated) )
    props.put( "ip", sl.ip )

    val buffer = new ByteArrayOutputStream
    props.store( buffer, "")


    return buffer.toString
  }

  def getServiceLocationFromString( s : String) : ServiceLocation = {
    val buffer = new ByteArrayInputStream(s.getBytes("iso-8859-1"))
    val p = new Properties
    p.load(buffer)

    val readDdslDataVersion = p.get("ddslDataVersion")

    if( !ddslDataVersion.equals( readDdslDataVersion)){
      throw new Exception("Incompatible dataVersion. programVersion: " + ddslDataVersion + " readVersion: " + readDdslDataVersion)
    }

    val sl = ServiceLocation(
      p.getProperty("url"),
      p.getProperty("testUrl"),
      p.getProperty("quality").toDouble,
      dtf.parseDateTime(p.getProperty("lastUpdated")),
      p.getProperty("ip"))

    return sl
  }


}

trait Dao{

  def serviceUp( s : Service)
  def serviceDown( s : Service )
  def getSLs(id : ServiceId) : Array[ServiceLocation]

}


class ZDao (val hosts : String) extends Dao with Watcher {

  private val log = Logger.getLogger(getClass())


  val sessionTimeout = 5*60*1000

  val basePath = "/ddsl/services/"

  private val client = new ZooKeeper(hosts, sessionTimeout, this)


  private def getSidPath( sid : ServiceId ) : String = {
    return basePath + sid.environment + "/" + sid.serviceType + "/" + sid.name + "/" + sid.version
  }

  def disconnect{
    log.info("Disconnecting from zookeeper - all services will be marked as offline")
    client.close
  }


  override def serviceUp( s : Service) {
    
    val path = getSidPath(s.id)
    val infoString = DdslDataConverter.getServiceLocationAsString( s.sl )

    validateAndCreate( path )


    val statusPath = getSLInstancePath( path, s.sl)

    log.debug("Writing status to path: " + statusPath)


    //just check if it exsists - if it does delete it, then insert it.

    //TODO: is it possible to update instead of delete/create?
    val stat = client.exists( statusPath, false)
    if( stat != null ){
      log.debug("statusnode exists - delete it before creating it")
      try{
        client.delete(statusPath, stat.getVersion)
      }catch{
        case e:Exception => None // ignoring it..
      }
    }

    log.debug("status: " + infoString)
    

    client.create( statusPath, infoString.getBytes("utf-8"), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL )
  }

  override def serviceDown( s : Service ) {

    val path = getSidPath(s.id)
    val statusPath = getSLInstancePath( path, s.sl)

    log.debug("trying to delete path: " + statusPath)
    val stat = client.exists( statusPath, false)
    if( stat != null ){
      log.debug("Deleting path: " + statusPath)
      try{
        client.delete( statusPath, stat.getVersion)
      }catch{
        case e: Exception => log.info("Error deleting path: " + statusPath, e)
      }

    }


  }

  private def getSLInstancePath( sidPath : String, sl : ServiceLocation) : String = {
    //url is the key to this instance of this service
    //it must be urlencoded to be a valid path-node-name
    val encodedUrl = new URLCodec().encode(sl.url)

    return sidPath + "/" + encodedUrl

  }

  private def validateAndCreate( path : String) {

    //skip the first /
    val pathParts = path.substring(1).split("/")

    validateAndCreate( "/", pathParts )
  }

  private def validateAndCreate( parentPath : String, restPathParts : Array[String]) {

    val path = parentPath + restPathParts(0)

    log.debug("Checking path: " + path)

    if( client.exists(path, false) == null ){

      log.debug("Creating path: " + path)
      //must create it
      client.create( path, Array(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT)
    }


    val rest = restPathParts.toList.slice(1, restPathParts.length)

    if( rest.length > 0 ){
      validateAndCreate( path + "/", rest.toArray)
    }

  }

  override def getSLs(id : ServiceId) : Array[ServiceLocation] = {

    def getInfoString( path : String ) : String = {

      try{
        val stat = new Stat
        val bytes = client.getData( path, false, stat)
        new String( bytes, "utf-8")
      }catch{
        case _ => null //return null if error - node might have gone offline since we created the list
      }

    }

    val sidPath = getSidPath( id )

    val slList = client.getChildren( sidPath, false).map { path : String => {

      val string = getInfoString( sidPath+"/"+path )

      if( string != null ) {
        DdslDataConverter.getServiceLocationFromString( string )
      }else{
        null
      }
    }}.filter { _ != null} //remove all that was null (error while reading)

    

    return slList.toArray
  }




  def process( event: WatchedEvent){
    log.info("got watch: " + event)
  }

  

}




object ZDaoTestMain{

  def main ( args : Array[String]){
    val log = Logger.getLogger( getClass )
    try{
      doStuff
    }catch{
      case x:Exception => log.error("error", x)
    }


  }

  def doStuff {
    println("hw")

    val hosts = "localhost:2181"
    val dao = new ZDao( hosts )

    val sid = ServiceId("test", "http", "testService", "1.0")
    val sl = ServiceLocation("http://localhost/url", "http://localhost/test", 10.0, new DateTime(), "127.0.0.1")
    Thread.sleep( 100 )
    val sl2 = ServiceLocation("http://localhost:90/url", "http://localhost:90/test", 9.0, new DateTime(), "127.0.0.1")

    val s = Service(sid,sl)

    dao.serviceUp( s )
    dao.serviceUp( Service(sid,sl2) )

    println(">>start list")
    dao.getSLs( sid).foreach{println( _ )}
    println("<<end list")

    dao.serviceDown( s )

    println(">>start list")
    dao.getSLs( sid).foreach{println( _ )}
    println("<<end list")

    Thread.sleep(100000)
  }
}
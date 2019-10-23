package com.mchange.sc.v1.sbtethereum.util

import com.mchange.sc.v1.sbtethereum.SbtKeyLoggerNamePrefix

import com.mchange.v2.log.{FallbackMLog,MLevel,MLog}

import scala.collection._

object MLogToggler {
  private val DefaultDetailPrefixes = immutable.Set(
    "com.mchange.sc.v1.sbtethereum",
    "com.mchange.sc.v1.consuela",
    "com.mchange.sc.v2.jsonrpc"
  )
  class withMutableDetailPrefixes extends MLogToggler {
    //MT protected with this' lock
    private [this] var _detailPrefixes : immutable.Set[String] = DefaultDetailPrefixes

    def detailPrefixes : immutable.Set[String] = this.synchronized( _detailPrefixes )

    def updateDetailPrefixes( prefixes : immutable.Set[String] ) : Unit = this.synchronized {
      this._detailPrefixes = prefixes
    }

    def appendDetailPrefix( prefix : String ) : Unit = this.synchronized {
      this._detailPrefixes = (this._detailPrefixes + prefix)
    }

    def clearDetailPrefixes() : Unit = updateDetailPrefixes( immutable.Set.empty[String] )

    def resetDetailPrefixes() : Unit = updateDetailPrefixes( DefaultDetailPrefixes )

    private def logDetails( name : String ) = this.synchronized {
      name != null && _detailPrefixes.find( pfx => name.startsWith( pfx ) ).nonEmpty
    }

    // for prefixes not specifically requested, we log at INFO, but we do not log "sbtkey." prefixed values because they will
    // already have been output to the console by sbt
    override def isFallbackLoggable( level : MLevel, loggerName : String, srcClass : String, srcMeth : String, msg : String, params : Array[Object], t : Throwable) : Boolean = {
      val ln = if (loggerName == null ) "" else loggerName
      logDetails( ln ) || (!ln.startsWith( SbtKeyLoggerNamePrefix ) && level.isLoggable( com.mchange.v2.log.MLevel.INFO ))
    }

    override def reset() : Unit = this.synchronized {
      resetDetailPrefixes()
      super.reset()
    }
  }
}
abstract class MLogToggler {

  def isFallbackLoggable( level : com.mchange.v2.log.MLevel, loggerName : String, srcClass : String, srcMeth : String, msg : String, params : Array[Object], t : Throwable) : Boolean

  lazy val fallback = {
    val fb = new FallbackMLog()
    fb.setOverrideCutoffLevel( MLevel.ALL ) // make sure we see everything, filter what we don't want
    fb.setGlobalFilter(
      new FallbackMLog.Filter {
        def isLoggable( level : MLevel, loggerName : String, srcClass : String, srcMeth : String, msg : String, params : Array[Object], t : Throwable) : Boolean = {
          isFallbackLoggable( level, loggerName, srcClass, srcMeth, msg, params, t )
        }
      }
    )
    fb
  }

  var replaced : MLog = null;

  def toggle() : Unit = this.synchronized {
    val current = MLog.instance()
    if ( current == fallback ) {
      assert( replaced != null, s"We should have a reference to the MLog instance we replaced!" )
      MLog.forceMLog( replaced );
      replaced = null;
    }
    else {
      assert( replaced == null, s"The default MLog is in use, we should not hold a replacement." )
      replaced = MLog.forceMLog( fallback )
    }
  }

  def directToFallback() : Unit = {
    val current = MLog.instance()
    if ( current != fallback ) toggle()
  }

  def directToDefault() : Unit = this.synchronized {
    val current = MLog.instance()
    if ( current == fallback ) toggle()
  }

  def reset() : Unit = directToDefault()
}

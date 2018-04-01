package com.mchange.sc.v1.sbtethereum.repository

import java.io.File
import java.sql.{Connection, Timestamp}
import java.text.SimpleDateFormat
import java.util.Date
import javax.sql.DataSource
import scala.collection._
import com.mchange.v2.c3p0.ComboPooledDataSource
import com.mchange.sc.v1.sbtethereum.repository
import com.mchange.sc.v1.sbtethereum.util.BaseCodeAndSuffix
import com.mchange.sc.v2.sql._
import com.mchange.sc.v2.failable._
import com.mchange.sc.v1.log.MLevel._
import com.mchange.sc.v1.consuela._
import com.mchange.sc.v1.consuela.ethereum.{EthAddress, EthHash, jsonrpc}
import jsonrpc.{Abi, Compilation}
import com.mchange.sc.v2.ens.{Bid,BidStore}
import com.mchange.sc.v2.lang.borrow
import com.mchange.sc.v1.consuela.io.ensureUserOnlyDirectory
import play.api.libs.json.Json

object Database {
  import Schema_h2._

  private val DirName = "database"

  private var _Directory : Failable[File] = null

  private var _DataSource : Failable[ComboPooledDataSource] = null
  private var _UncheckedDataSource : Failable[ComboPooledDataSource] = null;

  private [sbtethereum]
  def Directory : Failable[File] = this.synchronized {
    if ( _Directory == null ) {
      _Directory = repository.Directory.flatMap( mainDir => ensureUserOnlyDirectory( new File( mainDir, DirName ) ) )
    }
    _Directory
  }

  private [sbtethereum]
  def DataSource : Failable[ComboPooledDataSource] = this.synchronized {
    if ( _DataSource == null ) {
      _DataSource = h2.initializeDataSource( true )
    }
    _DataSource
  }
  private [sbtethereum]
  def UncheckedDataSource : Failable[ComboPooledDataSource] = this.synchronized {
    if ( _UncheckedDataSource == null ) {
      _UncheckedDataSource = h2.initializeDataSource( false )
    }
    _UncheckedDataSource
  }

  private [sbtethereum]
  def reset() : Unit = this.synchronized {
    _Directory = null
    if ( _DataSource != null ) {
      _DataSource.foreach { _.close() }
      _DataSource = null
    }
    if ( _UncheckedDataSource != null ) {
      _UncheckedDataSource.foreach { _.close() }
      _UncheckedDataSource = null
    }
  }

  private [sbtethereum]
  def insertCompilation(
    code              : String,
    mbName            : Option[String] = None,
    mbSource          : Option[String] = None,
    mbLanguage        : Option[String] = None,
    mbLanguageVersion : Option[String] = None,
    mbCompilerVersion : Option[String] = None,
    mbCompilerOptions : Option[String] = None,
    mbAbi             : Option[String] = None,
    mbUserDoc         : Option[String] = None,
    mbDeveloperDoc    : Option[String] = None,
    mbMetadata        : Option[String] = None
  ) : Failable[Unit] = {
    DataSource.flatMap { ds =>
      Failable {
        val bcas = BaseCodeAndSuffix( code )
        borrowTransact( ds.getConnection() ) { conn =>
          Table.KnownCode.upsert( conn, bcas.baseCodeHex )
          Table.KnownCompilations.upsert(
            conn,
            bcas.baseCodeHash,
            bcas.fullCodeHash,
            bcas.codeSuffixHex,
            mbName,
            mbSource,
            mbLanguage,
            mbLanguageVersion,
            mbCompilerVersion,
            mbCompilerOptions,
            mbAbi.map( abiStr => Json.parse( abiStr ).as[Abi] ),
            mbUserDoc.map( userDoc => Json.parse( userDoc ).as[Compilation.Doc.User] ),
            mbDeveloperDoc.map( developerDoc => Json.parse( developerDoc ).as[Compilation.Doc.Developer] ),
            mbMetadata
          )
        }
      }
    }
  }

  private [sbtethereum]
  def insertNewDeployment( blockchainId : String, contractAddress : EthAddress, code : String, deployerAddress : EthAddress, transactionHash : EthHash, constructorInputs : immutable.Seq[Byte] ) : Failable[Unit] = {
    DataSource.flatMap { ds =>
      Failable {
        borrow( ds.getConnection() ){ conn =>
          Table.DeployedCompilations.insertNewDeployment( conn, blockchainId, contractAddress, code, deployerAddress, transactionHash, constructorInputs )
        }
      }
    }
  }

  private [sbtethereum]
  def setMemorizedContractAbi( blockchainId : String, contractAddress : EthAddress, abi : Abi ) : Failable[Unit] = {
    DataSource.flatMap { ds =>
      Failable {
        borrow( ds.getConnection() ){ conn =>
          Table.MemorizedAbis.insert( conn, blockchainId, contractAddress, abi )
        }
      }
    }
  }

  private [sbtethereum]
  def deleteMemorizedContractAbi( blockchainId : String, contractAddress : EthAddress ) : Failable[Boolean] = {
    DataSource.flatMap { ds =>
      Failable {
        borrow( ds.getConnection() ){ conn =>
          Table.MemorizedAbis.delete( conn, blockchainId, contractAddress )
        }
      }
    }
  }

  private [sbtethereum]
  def getMemorizedContractAbiAddresses( blockchainId : String ) : Failable[immutable.Seq[EthAddress]] = {
    DataSource.flatMap { ds =>
      Failable {
        borrow( ds.getConnection() ){ conn =>
          Table.MemorizedAbis.selectAddressesForBlockchainId( conn, blockchainId )
        }
      }
    }
  }

  private [sbtethereum]
  def getMemorizedContractAbi( blockchainId : String, contractAddress : EthAddress ) : Failable[Option[Abi]] = {
    DataSource.flatMap { ds =>
      Failable {
        borrow( ds.getConnection() ){ conn =>
          Table.MemorizedAbis.select( conn, blockchainId, contractAddress )
        }
      }
    }
  }

  private [sbtethereum]
  def updateContractDatabase( compilations : Iterable[(String,jsonrpc.Compilation.Contract)] ) : Failable[Boolean] = {
    val ( compiledContracts, stubsWithDups ) = compilations.partition { case ( name, compilation ) => compilation.code.decodeHex.length > 0 }

    stubsWithDups.foreach { case ( name, compilation ) =>
      DEBUG.log( s"Contract '$name' is a stub or abstract contract, and so has not been incorporated into repository compilations." )( repository.logger )
    }

    def updateKnownContracts( conn : Connection ) : Failable[Boolean] = {
      def doUpdate( conn : Connection, contractTuple : Tuple2[String,jsonrpc.Compilation.Contract] ) : Failable[Boolean] = Failable {
        val ( name, compilation ) = contractTuple

        val code = compilation.code
        val bcas = BaseCodeAndSuffix( code )


        import compilation.info._

        Table.KnownCode.upsert( conn, bcas.baseCodeHex )

        val newCompilation = Table.KnownCompilations.KnownCompilation(
          fullCodeHash      = bcas.fullCodeHash,
          baseCodeHash      = bcas.baseCodeHash,
          codeSuffix        = bcas.codeSuffixHex,
          mbName            = Some( name ),
          mbSource          = mbSource,
          mbLanguage        = mbLanguage,
          mbLanguageVersion = mbLanguageVersion,
          mbCompilerVersion = mbCompilerVersion,
          mbCompilerOptions = mbCompilerOptions,
          mbAbi             = mbAbi,
          mbUserDoc         = mbUserDoc,
          mbDeveloperDoc    = mbDeveloperDoc,
          mbMetadata        = mbMetadata
        )

        val mbKnownCompilation = Table.KnownCompilations.select( conn, bcas.fullCodeHash )

        mbKnownCompilation match {
          case Some( kc ) => {
            if ( kc != newCompilation ) {
              Table.KnownCompilations.upsert( conn, newCompilation reconcileOver kc )
              true
            }
            else false
          }
          case None => {
            Table.KnownCompilations.upsert( conn, newCompilation )
            true
          }
        }
      }
      compiledContracts.toSeq.foldLeft( succeed( false ) )( ( failable, tup ) => failable.flatMap( last => doUpdate( conn, tup ).map( next => last || next ) ) )
    }

    DataSource.flatMap { ds =>
      borrowTransact( ds.getConnection() )( updateKnownContracts )
    }
  }

  private [sbtethereum]
  case class DeployedContractInfo (
    blockchainId        : String,
    contractAddress     : EthAddress,
    codeHash            : EthHash,
    code                : String,
    mbDeployerAddress   : Option[EthAddress],
    mbTransactionHash   : Option[EthHash],
    mbDeployedWhen      : Option[Long],
    mbConstructorInputs : Option[immutable.Seq[Byte]],
    mbName              : Option[String],
    mbSource            : Option[String],
    mbLanguage          : Option[String],
    mbLanguageVersion   : Option[String],
    mbCompilerVersion   : Option[String],
    mbCompilerOptions   : Option[String],
    mbAbi               : Option[Abi],
    mbUserDoc           : Option[Compilation.Doc.User],
    mbDeveloperDoc      : Option[Compilation.Doc.Developer],
    mbMetadata          : Option[String]
  )

  private [sbtethereum]
  def deployedContractInfoForAddress( blockchainId : String, address : EthAddress ) : Failable[Option[DeployedContractInfo]] =  {
    DataSource.flatMap { ds =>
      Failable {
        borrow( ds.getConnection ) { conn =>
          for {
            deployedCompilation <- Table.DeployedCompilations.select( conn, blockchainId, address )
            knownCode           <- Table.KnownCode.select( conn, deployedCompilation.baseCodeHash )
            knownCompilation    <- Table.KnownCompilations.select( conn, deployedCompilation.fullCodeHash )
          } yield {
            DeployedContractInfo (
              blockchainId         = deployedCompilation.blockchainId,
              contractAddress      = deployedCompilation.contractAddress,
              codeHash             = deployedCompilation.fullCodeHash,
              code                 = knownCode ++ knownCompilation.codeSuffix,
              mbDeployerAddress    = deployedCompilation.mbDeployerAddress,
              mbTransactionHash    = deployedCompilation.mbTransactionHash,
              mbDeployedWhen       = deployedCompilation.mbDeployedWhen,
              mbConstructorInputs  = deployedCompilation.mbConstructorInputs,
              mbName               = knownCompilation.mbName,
              mbSource             = knownCompilation.mbSource,
              mbLanguage           = knownCompilation.mbLanguage,
              mbLanguageVersion    = knownCompilation.mbLanguageVersion,
              mbCompilerVersion    = knownCompilation.mbCompilerVersion,
              mbCompilerOptions    = knownCompilation.mbCompilerOptions,
              mbAbi                = knownCompilation.mbAbi,
              mbUserDoc            = knownCompilation.mbUserDoc,
              mbDeveloperDoc       = knownCompilation.mbDeveloperDoc,
              mbMetadata           = knownCompilation.mbMetadata
            )
          }
        }
      }
    }
  }

  private [sbtethereum]
  def allDeployedContractInfosForBlockchainId( blockchainId : String ) : Failable[immutable.Seq[DeployedContractInfo]] = {

    def deployedContractInfosForAddresses( addresses : immutable.Seq[EthAddress] ) : Failable[immutable.Seq[DeployedContractInfo]] = {
      Failable.sequence( addresses.map( deployedContractInfoForAddress( blockchainId, _ ) ) ).map( optSeq => optSeq.map( opt => opt.get ) ) // asserts that all deploymens are known compilations
    }

    DataSource.flatMap { ds =>
      borrow( ds.getConnection ) { conn =>
        Failable( Table.DeployedCompilations.allAddressesForBlockchainIdSeq( conn, blockchainId ) ) flatMap { addresses =>
          deployedContractInfosForAddresses( addresses )
        }
      }
    }
  }

  private [sbtethereum]
  case class CompilationInfo (
    codeHash          : EthHash,
    code              : String,
    mbName            : Option[String],
    mbSource          : Option[String],
    mbLanguage        : Option[String],
    mbLanguageVersion : Option[String],
    mbCompilerVersion : Option[String],
    mbCompilerOptions : Option[String],
    mbAbi             : Option[Abi],
    mbUserDoc         : Option[Compilation.Doc.User],
    mbDeveloperDoc    : Option[Compilation.Doc.Developer],
    mbMetadata        : Option[String]
  )

  private [sbtethereum]
  def compilationInfoForCodeHash( codeHash : EthHash ) : Failable[Option[CompilationInfo]] =  {
    DataSource.flatMap { ds =>
      Failable {
        borrow( ds.getConnection ) { conn =>
          for {
            knownCompilation <- Table.KnownCompilations.select( conn, codeHash )
            knownCodeHex <- Table.KnownCode.select( conn, knownCompilation.baseCodeHash )
          } yield {
            CompilationInfo (
              codeHash          = codeHash,
              code              = knownCodeHex,
              mbName            = knownCompilation.mbName,
              mbSource          = knownCompilation.mbSource,
              mbLanguage        = knownCompilation.mbLanguage,
              mbLanguageVersion = knownCompilation.mbLanguageVersion,
              mbCompilerVersion = knownCompilation.mbCompilerVersion,
              mbCompilerOptions = knownCompilation.mbCompilerOptions,
              mbAbi             = knownCompilation.mbAbi,
              mbUserDoc         = knownCompilation.mbUserDoc,
              mbDeveloperDoc    = knownCompilation.mbDeveloperDoc,
              mbMetadata        = knownCompilation.mbMetadata
            )
          }
        }
      }
    }
  }

  private [sbtethereum]
  def contractAddressesForCodeHash( blockchainId : String, codeHash : EthHash ) : Failable[immutable.Set[EthAddress]] = {
    DataSource.flatMap { ds =>
      Failable {
        borrow( ds.getConnection() ) { conn =>
          Table.DeployedCompilations.allForFullCodeHash( conn, blockchainId, codeHash ).map( _.contractAddress )
        }
      }
    }
  }

  private [sbtethereum]
  def blockchainIdContractAddressesForCodeHash( codeHash : EthHash ) : Failable[immutable.Set[(String,EthAddress)]] = {
    DataSource.flatMap { ds =>
      Failable {
        borrow( ds.getConnection() ) { conn =>
          Table.DeployedCompilations.allForFullCodeHashAnyBlockchainId( conn, codeHash ).map( dc => ( dc.blockchainId, dc.contractAddress ) )
        }
      }
    }
  }

  private [sbtethereum]
  case class ContractsSummaryRow( blockchain_id : String, contract_address : String, name : String, deployer_address : String, code_hash : String, txn_hash : String, timestamp : String )

  private [sbtethereum]
  def contractsSummary : Failable[immutable.Seq[ContractsSummaryRow]] = {
    import ContractsSummary._

    DataSource.flatMap { ds =>
      Failable {
        borrow( ds.getConnection() ) { conn =>
          borrow( conn.createStatement() ) { stmt =>
            borrow( stmt.executeQuery( ContractsSummary.Sql ) ) { rs =>
              val buffer = new mutable.ArrayBuffer[ContractsSummaryRow]
              val df = new SimpleDateFormat( "yyyy-MM-dd'T'HH:mm:ss.SSSZ" )
              def mbformat( ts : Timestamp ) : String = if ( ts == null ) null else df.format( ts )
              while( rs.next() ) {
                buffer += ContractsSummaryRow(
                  rs.getString(Column.blockchain_id),
                  rs.getString(Column.contract_address),
                  rs.getString(Column.name),
                  rs.getString(Column.deployer_address),
                  rs.getString(Column.full_code_hash),
                  rs.getString(Column.txn_hash),
                  mbformat( rs.getTimestamp( Column.deployed_when ) )
                )
              }
              buffer.toVector
            }
          }
        }
      }
    }
  }

  private [sbtethereum]
  def cullUndeployedCompilations() : Failable[Int] = DataSource.flatMap { ds =>
    Failable {
      borrow( ds.getConnection() ) { conn =>
        borrow( conn.createStatement() ) { stmt =>
          stmt.executeUpdate( CullUndeployedCompilationsSql )
        }
      }
    }
  }

  private [sbtethereum]
  def createUpdateAlias( blockchainId : String, alias : String, address : EthAddress ) : Failable[Unit] = DataSource.flatMap { ds =>
    Failable( borrow( ds.getConnection() )( Table.AddressAliases.upsert( _, blockchainId, alias, address ) ) )
  }

  private [sbtethereum]
  def findAllAliases( blockchainId : String ) : Failable[immutable.SortedMap[String,EthAddress]] = DataSource.flatMap { ds =>
    Failable( borrow( ds.getConnection() )( Table.AddressAliases.selectAllForBlockchainId( _, blockchainId ) ) )
  }

  private [sbtethereum]
  def findAddressByAlias( blockchainId : String, alias : String ) : Failable[Option[EthAddress]] = DataSource.flatMap { ds =>
    Failable( borrow( ds.getConnection() )( Table.AddressAliases.selectByAlias( _, blockchainId, alias ) ) )
  }

  private [sbtethereum]
  def findAliasesByAddress( blockchainId : String, address : EthAddress ) : Failable[immutable.Seq[String]] = DataSource.flatMap { ds =>
    Failable( borrow( ds.getConnection() )( Table.AddressAliases.selectByAddress( _, blockchainId, address ) ) )
  }

  private [sbtethereum]
  def dropAlias( blockchainId : String, alias : String ) : Failable[Boolean] = DataSource.flatMap { ds =>
    Failable( borrow( ds.getConnection() )( Table.AddressAliases.delete( _, blockchainId, alias ) ) )
  }

  private [sbtethereum]
  def ensStoreBid( blockchainId : String, tld : String, ensAddress : EthAddress, bid : Bid ) : Failable[Unit] = DataSource.flatMap { ds =>
    Failable( borrow( ds.getConnection() )( Table.EnsBidStore.insert( _, blockchainId, bid.bidHash, bid.simpleName, bid.bidderAddress, bid.valueInWei, bid.salt, tld, ensAddress ) ) )
  }

  private [sbtethereum]
  def ensRemoveBid( blockchainId : String, bidHash : EthHash ) : Failable[Unit] = DataSource.flatMap { ds =>
    Failable( borrow( ds.getConnection() )( Table.EnsBidStore.markRemoved( _, blockchainId, bidHash ) ) )
  }

  private [sbtethereum]
  def ensMarkAccepted( blockchainId : String, bidHash : EthHash ) : Failable[Unit] = DataSource.flatMap { ds =>
    Failable( borrow( ds.getConnection() )( Table.EnsBidStore.markAccepted( _, blockchainId, bidHash ) ) )
  }

  private [sbtethereum]
  def ensMarkRevealed( blockchainId : String, bidHash : EthHash ) : Failable[Unit] = DataSource.flatMap { ds =>
    Failable( borrow( ds.getConnection() )( Table.EnsBidStore.markRevealed( _, blockchainId, bidHash ) ) )
  }

  private
  def ensBidStateFromRawBid( rawBid : Table.EnsBidStore.RawBid ) : BidStore.State = {
    ( rawBid.accepted, rawBid.revealed ) match {
      case ( _, true )     => BidStore.State.Revealed
      case ( true, false ) => BidStore.State.Accepted
      case _               => BidStore.State.Created
    }
  }

  private
  def ensBidBidStateTupleFromRawBid( rawBid  : Table.EnsBidStore.RawBid ) : Tuple2[ Bid, BidStore.State ] = {
    Tuple2( Bid( rawBid.bidHash, rawBid.simpleName, rawBid.bidderAddress, rawBid.valueInWei, rawBid.salt ), ensBidStateFromRawBid( rawBid ) )
  }

  private [sbtethereum]
  def ensFindByHash( blockchainId : String, bidHash : EthHash ) : Failable[( Bid, BidStore.State )] = DataSource.flatMap { ds =>
    Failable {
      borrow( ds.getConnection() ) { conn =>
        val mbRaw = Table.EnsBidStore.selectByBidHash( conn, blockchainId, bidHash )
        mbRaw.fold( fail( s"Bid hash '0x${bidHash.hex} does not exist in the database." ) : Failable[( Bid, BidStore.State )] ) { rawBid =>
          if ( rawBid.removed ) {
            fail( s"Bid hash '0x${bidHash.hex} did exist in the database, but it has been removed.")
          }
          else {
            succeed( ensBidBidStateTupleFromRawBid( rawBid ) )
          }
        }
      }
    }.flatten
  }

  private [sbtethereum]
  def ensFindByNameBidderAddress( blockchainId : String, simpleName : String, bidderAddress : EthAddress ) : Failable[immutable.Seq[(Bid, BidStore.State)]] = DataSource.flatMap { ds =>
    Failable {
      borrow( ds.getConnection() ) { conn =>
        val rawBids = Table.EnsBidStore.selectByNameBidderAddress( conn, blockchainId, simpleName, bidderAddress )
        rawBids.filterNot( _.removed ).map( ensBidBidStateTupleFromRawBid )
      }
    }
  }

  private [sbtethereum]
  def ensAllRawBidsForBlockchainId( blockchainId : String ) : Failable[immutable.Seq[Table.EnsBidStore.RawBid]] = DataSource.flatMap { ds =>
    Failable {
      borrow( ds.getConnection() ) { conn =>
        Table.EnsBidStore.selectAllForBlockchainId( conn, blockchainId )
      }
    }
  }

  private [sbtethereum]
  def ensBidStore( blockchainId : String, tld : String, ensAddress : EthAddress ) = new BidStore {
    def store( bid : Bid ) : Unit = ensStoreBid( blockchainId, tld, ensAddress, bid ).get
    def remove( bid : Bid ) : Unit = ensRemoveBid( blockchainId, bid.bidHash ).get
    def markAccepted( bidHash : EthHash ) : Unit = ensMarkAccepted( blockchainId, bidHash ).get
    def markRevealed( bidHash : EthHash ) : Unit = ensMarkRevealed( blockchainId, bidHash ).get
    def findByHash( bidHash : EthHash ) : ( Bid, BidStore.State ) = ensFindByHash( blockchainId, bidHash ).get
    def findByNameBidderAddress( simpleName : String, bidderAddress : EthAddress ) : immutable.Seq[( Bid, BidStore.State )] = {
      ensFindByNameBidderAddress( blockchainId, simpleName, bidderAddress ).get
    }
  }

  private [sbtethereum]
  def backupDatabaseH2( conn : Connection, schemaVersion : Int ) : Failable[Unit] = h2.makeBackup( conn, schemaVersion )

  private final object h2 {
    val DirName = "h2"
    val DbName  = "sbt-ethereum"

    private val BackupsDirName = "h2-backups"

    lazy val Directory : Failable[File] = Database.Directory.flatMap( dbDir => ensureUserOnlyDirectory( new File( dbDir, DirName ) ) )

    lazy val DbAsFile : Failable[File] = Directory.map( dir => new File( dir, DbName ) ) // the db will make files of this name, with various suffixes appended

    lazy val BackupsDir : Failable[File] = Database.Directory.flatMap( dbDir => ensureUserOnlyDirectory( new File( dbDir, BackupsDirName ) ) )

    lazy val JdbcUrl : Failable[String] = h2.DbAsFile.map( f => s"jdbc:h2:${f.getAbsolutePath};AUTO_SERVER=TRUE" )

    def initializeDataSource( ensureSchema : Boolean ) : Failable[ComboPooledDataSource] = {
      for {
        _       <- Directory
        jdbcUrl <- JdbcUrl
      } yield {
        val ds = new ComboPooledDataSource
        try {
          ds.setDriverClass( "org.h2.Driver" )
          ds.setJdbcUrl( jdbcUrl )
          ds.setTestConnectionOnCheckout( true )
          if ( ensureSchema ) Schema_h2.ensureSchema( ds )
          ds
        } catch {
          case t : Throwable =>
            try ds.close() catch suppressInto(t)
            throw t
        }
      }
    }

    private def suppressInto( original : Throwable ) : PartialFunction[Throwable,Unit] = {
      case t : Throwable => original.addSuppressed( t )
    }

    def makeBackup( conn : Connection, schemaVersion : Int ) : Failable[Unit] = {
      BackupsDir.map { pmbDir =>
        val df = new SimpleDateFormat("yyyyMMdd'T'HHmmssZ")
        val ts = df.format( new Date() )
        val targetFile = new File( pmbDir, s"$DbName-v$schemaVersion-$ts.sql" )
        borrow( conn.prepareStatement( s"SCRIPT TO '${targetFile.getAbsolutePath}' CHARSET 'UTF8'" ) )( _.executeQuery().close() ) // we don't need the result set, just want the file
      }
    }
  }
}

package com.mchange.sc.v1.sbtethereum.mutables

import com.mchange.sc.v1.sbtethereum.{compile,signer,util}
import compile.Compiler
import signer.SignersManager
import util.warner._
import util.ChainIdMutable

import com.mchange.sc.v1.consuela.ethereum.{jsonrpc, EthAddress, EthPrivateKey}

import com.mchange.sc.v2.concurrent.Scheduler

import scala.collection._

import scala.sys.process.Process

import java.io.File

import java.util.concurrent.atomic.AtomicReference

private [sbtethereum] final class Raw (
  scheduler            : Scheduler,
  keystoresV3          : immutable.Seq[File],
  publicTestAddresses  : immutable.Map[EthAddress,EthPrivateKey],
  abiOverridesForChain : Int => immutable.Map[EthAddress,jsonrpc.Abi],
  maxUnlockedAddresses : Int
) {

  val MainSignersManager = new SignersManager( scheduler, keystoresV3, publicTestAddresses, abiOverridesForChain, maxUnlockedAddresses )

  val SessionSolidityCompilers = new AtomicReference[Option[immutable.Map[String,Compiler.Solidity]]]( None )

  val CurrentSolidityCompiler = new AtomicReference[Option[( String, Compiler.Solidity )]]( None )

  // MT: protected by ChainIdOverride' lock
  val ChainIdOverride = new AtomicReference[Option[Int]]( None ) // Only supported for Compile config

  // MT: internally thread-safe
  val SenderOverrides = new ChainIdMutable[EthAddress]

  // MT: internally thread-safe
  val NodeUrlOverrides = new ChainIdMutable[String]

  // MT: internally thread-safe
  val AbiOverrides = new ChainIdMutable[immutable.Map[EthAddress,jsonrpc.Abi]]

  // MT: internally thread-safe
  val GasLimitTweakOverrides = new ChainIdMutable[jsonrpc.Invoker.MarkupOrOverride]

  // MT: internally thread-safe
  val GasPriceTweakOverrides = new ChainIdMutable[jsonrpc.Invoker.MarkupOrOverride]

  // MT: internally thread-safe
  val NonceOverrides = new ChainIdMutable[BigInt]

  // MT: internally thread-safe
  val OneTimeWarner = new OneTimeWarner[OneTimeWarnerKey]

  // MT: protected by LocalGanache's lock
  val LocalGanache = new AtomicReference[Option[Process]]( None )

  def reset() : Unit = {
    MainSignersManager.reset()
    SessionSolidityCompilers.set( None )
    CurrentSolidityCompiler.set( None )
    ChainIdOverride.set( None )
    SenderOverrides.reset()
    NodeUrlOverrides.reset()
    AbiOverrides.reset()
    GasLimitTweakOverrides.reset()
    GasPriceTweakOverrides.reset()
    NonceOverrides.reset()
    OneTimeWarner.resetAll()
    LocalGanache synchronized {
      LocalGanache.set( None )
    }
  }
}


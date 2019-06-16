package com.mchange.sc.v1.sbtethereum

import com.mchange.sc.v1.consuela.ethereum.{EthAddress,EthPrivateKey}

/*
 * Instances should not Store the private key, but
 * construct it interactively from safe antecedents
 */ 
private class PrivateKeyFinder( val address : EthAddress, findOp : () => EthPrivateKey ) {
  def find() = findOp().ensuring( privateKey => address == privateKey.address )
}

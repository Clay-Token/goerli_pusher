package net.goerli.pusher

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filter
import org.kethereum.DEFAULT_GAS_PRICE
import org.kethereum.crypto.createEthereumKeyPair
import org.kethereum.eip155.signViaEIP155
import org.kethereum.erc681.ERC681
import org.kethereum.erc681.generateURL
import org.kethereum.flows.getTransactionFlow
import org.kethereum.functions.encodeRLP
import org.kethereum.keystore.api.InitializingFileKeyStore
import org.kethereum.model.Address
import org.kethereum.model.ChainId
import org.kethereum.rpc.EthereumRPCException
import org.kethereum.rpc.min3.GOERLI_BOOTNODES
import org.kethereum.rpc.min3.MIN3RPC
import org.komputing.kethereum.erc1450.ERC1450TransactionGenerator
import org.walleth.console.barcodes.printQR
import org.walleth.khex.toHexString
import java.io.File
import java.math.BigInteger
import java.math.BigInteger.ZERO

private val FAUCET_ADDRESS = listOf(
    Address("0x8ced5ad0d8da4ec211c17355ed3dbfec4cf0e5b9"), // simple
    Address("0x8c1e1e5b47980d214965f3bd8ea34c413e120ae4") // social
)

val TOKEN_CONTRACT_ADDRESS = Address("0x7af963cF6D228E564e2A0aA0DdBF06210B38615D")
val chain = ChainId(5)
val rpc = MIN3RPC(GOERLI_BOOTNODES)

val keyStore = InitializingFileKeyStore(File("keystore")).apply {
    if (getAddresses().isEmpty()) {
        println("creating new keypair")
        addKey(createEthereumKeyPair())
    }
}

val address = keyStore.getAddresses().first()
val keyPair = keyStore.getKeyForAddress(address)!!

suspend fun main() {
    val erc681 = ERC681(address = address.hex)
    printQR(erc681.generateURL())
    println(address.cleanHex)

    getTransactionFlow(rpc).filter { tx ->
        FAUCET_ADDRESS.any { address -> tx.transaction.from == address }
    }.collect { tx ->
        print("\n")
        println("tx from faucet: " + tx.transaction.to)

        tx.transaction.to?.let { toAddress ->
            sendTransaction(toAddress)
        }
    }
}

private suspend fun retrySendTransaction(toAddress: Address) {
    println("Will retry to send to $toAddress shortly")
    delay(420)
    sendTransaction(toAddress)
}

private suspend fun sendTransaction(toAddress: Address) {
    val txCount = rpc.getTransactionCount(address.hex, "latest") ?: return retrySendTransaction(address)

    val mintableToken = ERC1450TransactionGenerator(TOKEN_CONTRACT_ADDRESS)

    val transaction = mintableToken.mint(toAddress, BigInteger("420000000000000000000")).copy(
        chain = chain.value,
        value = ZERO,
        nonce = txCount,
        gasPrice = DEFAULT_GAS_PRICE,
        gasLimit = BigInteger("100000")
    )
    val signature = transaction.signViaEIP155(keyPair, chain)

    val tx = transaction.encodeRLP(signature).toHexString()

    try {
        val result = rpc.sendRawTransaction(tx)
        if (result == null) {
            println("Problem sending transaction")
        } else {
            println("sending tx OK ($result)")
        }
    } catch (rpcException: EthereumRPCException) {
        println("send tx error " + rpcException.message)
        retrySendTransaction(address)
    }
}

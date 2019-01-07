package net.corda.node.services.persistence

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.contracts.*
import net.corda.core.flows.*
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.Party
import net.corda.core.node.StatesToRecord
import net.corda.core.schemas.MappedSchema
import net.corda.core.schemas.PersistentState
import net.corda.core.schemas.QueryableState
import net.corda.core.serialization.CordaSerializable
import net.corda.core.transactions.LedgerTransaction
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker
import net.corda.core.utilities.getOrThrow
import net.corda.testing.core.ALICE_NAME
import net.corda.testing.core.BOB_NAME
import net.corda.testing.core.singleIdentity
import net.corda.testing.node.internal.InternalMockNetwork
import net.corda.testing.node.internal.enclosedCordapp
import net.corda.testing.node.internal.startFlow
import org.junit.After
import org.junit.Before
import org.junit.Test
import javax.persistence.Column
import javax.persistence.Entity
import javax.persistence.Table
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ObserverNodeTransactionTests {
    companion object {
        val MESSAGE_CHAIN_CONTRACT_PROGRAM_ID: String = MessageChainContract::class.java.name
    }

    private lateinit var mockNet: InternalMockNetwork

    @Before
    fun start() {
        mockNet = InternalMockNetwork(
                cordappsForAllNodes = listOf(enclosedCordapp()),
                networkSendManuallyPumped = false,
                threadPerNode = true)
    }

    @After
    fun cleanUp() {
        mockNet.stopNodes()
    }

    @Test
    fun `Broadcasting an old transaction does not cause 2 unconsumed states`() {
        val node = mockNet.createPartyNode(ALICE_NAME)
        val regulator = mockNet.createPartyNode(BOB_NAME)
        val notary = mockNet.defaultNotaryIdentity
        regulator.registerInitiatedFlow(ReceiveReportedTransaction::class.java)

        fun buildTransactionChain(initialMessage: MessageData, chainLength: Int) {
            node.services.startFlow(StartMessageChainFlow(initialMessage, notary)).resultFuture.getOrThrow()
            var result = node.services.vaultService.queryBy(MessageChainState::class.java).states.singleOrNull {
                it.state.data.message.value.startsWith(initialMessage.value)
            }

            for (_i in 0.until(chainLength -1 )) {
                node.services.startFlow(ContinueMessageChainFlow(result!!, notary)).resultFuture.getOrThrow()
                result = node.services.vaultService.queryBy(MessageChainState::class.java).states.singleOrNull {
                    it.state.data.message.value.startsWith(initialMessage.value)
                }
            }
        }

        fun sendTransactionToObserver(transactionIdx: Int) {
            val transactionList = node.services.validatedTransactions.track().snapshot
            node.services.startFlow(ReportToCounterparty(regulator.info.singleIdentity(), transactionList[transactionIdx])).resultFuture.getOrThrow()
        }

        fun checkObserverTransactions(expectedMessage: MessageData) {
            val regulatorStates = regulator.services.vaultService.queryBy(MessageChainState::class.java).states.filter {
                it.state.data.message.value.startsWith(expectedMessage.value[0])
            }

            assertNotNull(regulatorStates, "Could not find any regulator states")
            assertEquals(1, regulatorStates.size, "Incorrect number of unconsumed regulator states")
            val retrievedMessage = regulatorStates.singleOrNull()!!.state.data.message
            assertEquals(expectedMessage, retrievedMessage, "Final unconsumed regulator state is incorrect")
        }

        // Check that sending an old transaction doesn't result in a new unconsumed state
        val message = MessageData("A")
        buildTransactionChain(message, 4)
        sendTransactionToObserver(3)
        sendTransactionToObserver(1)
        val outputMessage = MessageData("AAAA")
        checkObserverTransactions(outputMessage)
    }

    @CordaSerializable
    data class MessageData(val value: String)

    data class MessageChainState(val message: MessageData, val by: Party, override val linearId: UniqueIdentifier = UniqueIdentifier()) : LinearState, QueryableState {
        override val participants: List<AbstractParty> = listOf(by)

        override fun generateMappedObject(schema: MappedSchema): PersistentState {
            return when (schema) {
                is MessageChainSchemaV1 -> MessageChainSchemaV1.PersistentMessage(
                        by = by.name.toString(),
                        value = message.value
                )
                else -> throw IllegalArgumentException("Unrecognised schema $schema")
            }
        }

        override fun supportedSchemas(): Iterable<MappedSchema> = listOf(MessageChainSchemaV1)
    }

    object MessageChainSchema
    object MessageChainSchemaV1 : MappedSchema(
            schemaFamily = MessageChainSchema.javaClass,
            version = 1,
            mappedTypes = listOf(PersistentMessage::class.java)) {

        @Entity
        @Table(name = "messages")
        class PersistentMessage(
                @Column(name = "message_by", nullable = false)
                var by: String,

                @Column(name = "message_value", nullable = false)
                var value: String
        ) : PersistentState()
    }

    open class MessageChainContract : Contract {
        override fun verify(tx: LedgerTransaction) {
            val command = tx.commands.requireSingleCommand<Commands.Send>()
            requireThat {
                // Generic constraints around the IOU transaction.
                "Only one output state should be created." using (tx.outputs.size == 1)
                val out = tx.outputsOfType<MessageChainState>().single()
                "Message sender must sign." using (command.signers.containsAll(out.participants.map { it.owningKey }))

                "Message value must not be empty." using (out.message.value.isNotBlank())
            }
        }

        interface Commands : CommandData {
            class Send : Commands
        }
    }

    @StartableByRPC
    class StartMessageChainFlow(private val message: MessageData, private val notary: Party) : FlowLogic<SignedTransaction>() {
        companion object {
            object GENERATING_TRANSACTION : ProgressTracker.Step("Generating transaction based on the message.")
            object VERIFYING_TRANSACTION : ProgressTracker.Step("Verifying contract constraints.")
            object SIGNING_TRANSACTION : ProgressTracker.Step("Signing transaction with our private key.")
            object FINALISING_TRANSACTION : ProgressTracker.Step("Obtaining notary signature and recording transaction.") {
                override fun childProgressTracker() = FinalityFlow.tracker()
            }

            fun tracker() = ProgressTracker(GENERATING_TRANSACTION, VERIFYING_TRANSACTION, SIGNING_TRANSACTION, FINALISING_TRANSACTION)
        }

        override val progressTracker = tracker()

        @Suspendable
        override fun call(): SignedTransaction {
            progressTracker.currentStep = GENERATING_TRANSACTION

            val messageState = MessageChainState(message = message, by = ourIdentity)
            val txCommand = Command(MessageChainContract.Commands.Send(), messageState.participants.map { it.owningKey })
            val txBuilder = TransactionBuilder(notary).withItems(StateAndContract(messageState, MESSAGE_CHAIN_CONTRACT_PROGRAM_ID), txCommand)

            progressTracker.currentStep = VERIFYING_TRANSACTION
            txBuilder.toWireTransaction(serviceHub).toLedgerTransaction(serviceHub).verify()

            progressTracker.currentStep = SIGNING_TRANSACTION
            val signedTx = serviceHub.signInitialTransaction(txBuilder)

            progressTracker.currentStep = FINALISING_TRANSACTION
            return subFlow(FinalityFlow(signedTx, emptyList(), FINALISING_TRANSACTION.childProgressTracker()))
        }
    }

    @StartableByRPC
    class ContinueMessageChainFlow(private val stateRef: StateAndRef<MessageChainState>,
                                   private val notary: Party) : FlowLogic<SignedTransaction>() {
        companion object {
            object GENERATING_TRANSACTION : ProgressTracker.Step("Generating transaction based on the message.")
            object VERIFYING_TRANSACTION : ProgressTracker.Step("Verifying contract constraints.")
            object SIGNING_TRANSACTION : ProgressTracker.Step("Signing transaction with our private key.")
            object FINALISING_TRANSACTION : ProgressTracker.Step("Obtaining notary signature and recording transaction.") {
                override fun childProgressTracker() = FinalityFlow.tracker()
            }

            fun tracker() = ProgressTracker(GENERATING_TRANSACTION, VERIFYING_TRANSACTION, SIGNING_TRANSACTION, FINALISING_TRANSACTION)
        }

        override val progressTracker = tracker()

        @Suspendable
        override fun call(): SignedTransaction {
            progressTracker.currentStep = GENERATING_TRANSACTION

            val oldMessageState = stateRef.state.data
            val messageState = MessageChainState(MessageData(oldMessageState.message.value + "A"),
                    ourIdentity,
                    stateRef.state.data.linearId)
            val txCommand = Command(MessageChainContract.Commands.Send(), messageState.participants.map { it.owningKey })
            val txBuilder = TransactionBuilder(notary).withItems(
                    StateAndContract(messageState, MESSAGE_CHAIN_CONTRACT_PROGRAM_ID),
                    txCommand,
                    stateRef)

            progressTracker.currentStep = VERIFYING_TRANSACTION
            txBuilder.toWireTransaction(serviceHub).toLedgerTransaction(serviceHub).verify()

            progressTracker.currentStep = SIGNING_TRANSACTION
            val signedTx = serviceHub.signInitialTransaction(txBuilder)

            progressTracker.currentStep = FINALISING_TRANSACTION
            return subFlow(FinalityFlow(signedTx, emptyList(), FINALISING_TRANSACTION.childProgressTracker()))
        }
    }

    @InitiatingFlow
    @StartableByRPC
    class ReportToCounterparty(
            private val regulator: Party,
            private val signedTx: SignedTransaction) : FlowLogic<Unit>() {

        @Suspendable
        override fun call() {
            val session = initiateFlow(regulator)
            subFlow(SendTransactionFlow(session, signedTx))
        }
    }

    @InitiatedBy(ReportToCounterparty::class)
    class ReceiveReportedTransaction(private val otherSideSession: FlowSession) : FlowLogic<Unit>() {

        @Suspendable
        override fun call() {
            subFlow(ReceiveTransactionFlow(otherSideSession, true, StatesToRecord.ALL_VISIBLE))
        }
    }
}

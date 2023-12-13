package com.ivy.wallet.domain.deprecated.logic.loantrasactions

import com.ivy.base.legacy.Transaction
import com.ivy.base.model.TransactionType
import com.ivy.data.model.LoanType
import com.ivy.legacy.datamodel.Account
import com.ivy.legacy.datamodel.Loan
import com.ivy.legacy.datamodel.LoanRecord
import com.ivy.legacy.datamodel.temp.toDomain
import com.ivy.legacy.domain.deprecated.logic.loantrasactions.LoanTransactionsCore
import com.ivy.legacy.utils.computationThread
import com.ivy.legacy.utils.scopedIOThread
import com.ivy.wallet.domain.deprecated.logic.model.CreateLoanData
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import java.util.UUID
import javax.inject.Inject

class LTLoanMapper @Inject constructor(
    private val ltCore: LoanTransactionsCore
) {

    suspend fun createAssociatedLoanTransaction(data: CreateLoanData, loanId: UUID) {
        computationThread {
            ltCore.updateAssociatedTransaction(
                createTransaction = data.createLoanTransaction,
                loanId = loanId,
                amount = data.amount,
                loanType = data.type,
                selectedAccountId = data.account?.id,
                title = data.name,
                isLoanRecord = false,
            )
        }
    }

    suspend fun editAssociatedLoanTransaction(
        loan: Loan,
        createLoanTransaction: Boolean = false,
        transaction: Transaction?
    ) {
        computationThread {
            ltCore.updateAssociatedTransaction(
                createTransaction = createLoanTransaction,
                loanId = loan.id,
                amount = loan.amount,
                loanType = loan.type,
                selectedAccountId = loan.accountId,
                title = loan.name,
                isLoanRecord = false,
                transaction = transaction,
                time = transaction?.dateTime
            )
        }
    }

    suspend fun deleteAssociatedLoanTransactions(loanId: UUID) {
        ltCore.deleteAssociatedTransactions(loanId = loanId)
    }

    suspend fun recalculateLoanRecords(
        oldLoanAccountId: UUID?,
        newLoanAccountId: UUID?,
        loanId: UUID
    ) {
        val accounts = ltCore.fetchAccounts().map { it.toDomain() }
        computationThread {
            if (oldLoanAccountId == newLoanAccountId || oldLoanAccountId.fetchAssociatedCurrencyCode(
                    accounts
                ) == newLoanAccountId.fetchAssociatedCurrencyCode(accounts)
            ) {
                return@computationThread
            }

            val newLoanRecords = calculateLoanRecords(
                loanId = loanId,
                newAccountId = newLoanAccountId,
            )

            ltCore.saveLoanRecords(newLoanRecords)
        }
    }

    suspend fun updateAssociatedLoan(
        transaction: Transaction?,
        onBackgroundProcessingStart: suspend () -> Unit = {},
        onBackgroundProcessingEnd: suspend () -> Unit = {},
        accountsChanged: Boolean = true
    ) {
        computationThread {
            transaction?.loanId ?: return@computationThread

            onBackgroundProcessingStart()

            val loan = ltCore.fetchLoan(transaction.loanId!!) ?: return@computationThread

            if (accountsChanged) {
                val newLoanRecords: List<LoanRecord> = calculateLoanRecords(
                    loanId = transaction.loanId!!,
                    newAccountId = transaction.accountId
                )
                ltCore.saveLoanRecords(newLoanRecords)
            }

            val modifiedLoan = loan.copy(
                amount = transaction.amount.toDouble(),
                name = if (transaction.title.isNullOrEmpty()) loan.name else transaction.title!!,
                type = if (transaction.type == TransactionType.INCOME) LoanType.BORROW else LoanType.LEND,
                accountId = transaction.accountId
            )

            ltCore.saveLoan(modifiedLoan.toDomain())
        }
        onBackgroundProcessingEnd()
    }

    private suspend fun calculateLoanRecords(
        newAccountId: UUID?,
        loanId: UUID
    ): List<LoanRecord> {
        return scopedIOThread { scope ->
            val loanRecords =
                ltCore.fetchAllLoanRecords(loanId = loanId)
                    .map { it.toDomain() }
                    .map { loanRecord ->
                        scope.async {
                            val convertedAmount: Double? =
                                ltCore.computeConvertedAmount(
                                    oldLoanRecordAccountId = loanRecord.accountId,
                                    oldLonRecordConvertedAmount = loanRecord.convertedAmount,
                                    oldLoanRecordAmount = loanRecord.amount,
                                    newLoanRecordAccountID = loanRecord.accountId,
                                    newLoanRecordAmount = loanRecord.amount,
                                    loanAccountId = newAccountId,
                                    accounts = ltCore.fetchAccounts().map { it.toDomain() },
                                )
                            loanRecord.copy(convertedAmount = convertedAmount)
                        }
                    }.awaitAll()
            loanRecords
        }
    }

    private suspend fun UUID?.fetchAssociatedCurrencyCode(accountsList: List<Account>): String {
        return ltCore.findAccount(accountsList, this)?.currency ?: ltCore.baseCurrency()
    }
}

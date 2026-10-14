package com.example.transactions.repository;


import com.example.transactions.entity.AccountBalance;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface AccountBalanceRepository extends JpaRepository<AccountBalance, Long> {
    Optional<AccountBalance> findByAccountIdAndCurrency(String accountId, String currency);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from AccountBalance b where b.accountId = :accountId and b.currency = :currency")
    Optional<AccountBalance> findWithLockByAccountIdAndCurrency(@Param("accountId") String accountId,
                                                                 @Param("currency") String currency);
}

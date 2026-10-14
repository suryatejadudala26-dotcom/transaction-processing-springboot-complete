package com.example.transactions.entity;
import jakarta.persistence.*;
import java.math.BigDecimal;
@Entity @Table(name="account_balances",uniqueConstraints=@UniqueConstraint(name="uk_account_currency",columnNames={"account_id","currency"}))
public class AccountBalance {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
 @Column(name="account_id",nullable=false,length=100) private String accountId;
 @Column(nullable=false,length=3) private String currency;
 @Column(nullable=false,precision=19,scale=2) private BigDecimal balance;
 @Version private long version;
 protected AccountBalance(){}
 public AccountBalance(String accountId,String currency){this.accountId=accountId;this.currency=currency;this.balance=BigDecimal.ZERO;}
 public void apply(BigDecimal delta){balance=balance.add(delta);}
 public String getAccountId(){return accountId;} public String getCurrency(){return currency;} public BigDecimal getBalance(){return balance;}
}

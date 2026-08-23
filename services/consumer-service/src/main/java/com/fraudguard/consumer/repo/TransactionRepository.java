package com.fraudguard.consumer.repo;

import com.fraudguard.consumer.model.TransactionRecord;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TransactionRepository extends JpaRepository<TransactionRecord, String> {
}

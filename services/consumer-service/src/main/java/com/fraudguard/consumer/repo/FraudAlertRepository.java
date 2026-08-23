package com.fraudguard.consumer.repo;

import com.fraudguard.consumer.model.FraudAlertRecord;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FraudAlertRepository extends JpaRepository<FraudAlertRecord, Long> {

    List<FraudAlertRecord> findByCreatedAtGreaterThanEqualOrderByCreatedAtDesc(Instant since);
}

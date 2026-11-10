package com.substring.docmind.repository;

import com.substring.docmind.entity.ConfluenceSyncRun;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ConfluenceSyncRunRepository extends JpaRepository<ConfluenceSyncRun, Long> {

    List<ConfluenceSyncRun> findAllByOrderByStartedAtDesc(Pageable pageable);

    Optional<ConfluenceSyncRun> findFirstByOrderByStartedAtDesc();

    Optional<ConfluenceSyncRun> findFirstByStatusInOrderByStartedAtDesc(List<ConfluenceSyncRun.Status> statuses);

    List<ConfluenceSyncRun> findByStatus(ConfluenceSyncRun.Status status);
}

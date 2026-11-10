package com.substring.docmind.repository;

import com.substring.docmind.entity.ConfluenceSyncedPage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ConfluenceSyncedPageRepository extends JpaRepository<ConfluenceSyncedPage, String> {

    List<ConfluenceSyncedPage> findBySpaceKey(String spaceKey);

    List<ConfluenceSyncedPage> findBySpaceKeyOrderByTitleAsc(String spaceKey);

    long countBySpaceKey(String spaceKey);

    @Query("select coalesce(sum(p.chunkCount), 0) from ConfluenceSyncedPage p where p.spaceKey = :spaceKey")
    long sumChunkCountBySpaceKey(@Param("spaceKey") String spaceKey);
}

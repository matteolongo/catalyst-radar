package com.catalystradar.persistence.ingestion

import org.springframework.data.domain.Pageable
import org.springframework.data.repository.ListCrudRepository
import java.util.UUID

interface IngestionRunRepository : ListCrudRepository<IngestionRunRow, UUID> {
    fun findAllByOrderByStartedAtDescIdDesc(pageable: Pageable): List<IngestionRunRow>
}

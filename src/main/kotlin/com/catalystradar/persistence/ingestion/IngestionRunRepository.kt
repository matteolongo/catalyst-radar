package com.catalystradar.persistence.ingestion

import org.springframework.data.repository.ListCrudRepository
import java.util.UUID

interface IngestionRunRepository : ListCrudRepository<IngestionRunRow, UUID>

package com.catalystradar.persistence.event

import org.springframework.data.repository.ListCrudRepository
import java.util.UUID

interface EventClusterRepository : ListCrudRepository<EventClusterRow, UUID>

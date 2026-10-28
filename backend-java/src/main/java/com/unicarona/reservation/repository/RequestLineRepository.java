package com.unicarona.reservation.repository;

import com.unicarona.reservation.entity.RequestLine;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RequestLineRepository extends JpaRepository<RequestLine,RequestLine.Key> {}

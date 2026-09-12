package com.wasparks.api.repository;

import com.wasparks.api.entity.WhatsAppAccountRef;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Read-only projection over the shared {@code whatsapp_accounts} table. This service never writes it. */
public interface WhatsAppAccountRefRepository extends JpaRepository<WhatsAppAccountRef, UUID> {

    /**
     * The ownership check behind every Meta-compatible send: the number must belong to <em>this</em>
     * tenant. Scoping the query by tenant rather than filtering after the fetch is what stops a key
     * sending from someone else's number even if it knows the id (epic §0.8).
     */
    Optional<WhatsAppAccountRef> findByPhoneNumberIdAndTenantId(String phoneNumberId, UUID tenantId);

    List<WhatsAppAccountRef> findByTenantId(UUID tenantId);
}

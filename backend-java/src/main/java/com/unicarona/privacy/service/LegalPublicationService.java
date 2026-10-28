package com.unicarona.privacy.service;

import com.unicarona.shared.repository.AuditRepository;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LegalPublicationService {
    private final JdbcTemplate sql;private final AuditRepository audit;
    public LegalPublicationService(JdbcTemplate sql,AuditRepository audit){this.sql=sql;this.audit=audit;}
    @Transactional public void publish(LegalDocumentTemplates.Bundle bundle){
        if(!"published".equals(bundle.publicationState()))throw new IllegalStateException("Draft legal documents cannot be published.");
        sql.execute("SET LOCAL lock_timeout='3s'");
        for(var doc:bundle.documents().stream().sorted(Comparator.comparing(LegalDocumentTemplates.Prepared::slug)).toList()){
            sql.update("INSERT INTO legal_document_versions(slug,version,title,content,content_sha256,change_summary,requires_acceptance,effective_at,revision,kind,required_scopes) VALUES(?,?,?,?::jsonb,?,?,?,now(),?,?,?::text[]) ON CONFLICT(slug,version) DO NOTHING",doc.slug(),doc.version(),doc.title(),doc.content(),doc.hash(),doc.summary(),doc.material(),doc.revision(),doc.kind(),"{"+String.join(",",doc.scopes())+"}");
            var stored=sql.queryForMap("SELECT content_sha256,content::text,revision FROM legal_document_versions WHERE slug=? AND version=?",doc.slug(),doc.version());if(!doc.hash().equals(stored.get("content_sha256")))throw new IllegalStateException("Published legal content identity conflict.");
            var heads=sql.queryForList("SELECT h.version,v.revision FROM legal_document_heads h JOIN legal_document_versions v ON v.slug=h.slug AND v.version=h.version WHERE h.slug=? FOR UPDATE OF h",doc.slug());
            if(!heads.isEmpty()){
                var head=heads.getFirst();int revision=((Number)head.get("revision")).intValue();if(doc.version().equals(head.get("version"))||revision>doc.revision())continue;
                if(revision==doc.revision())throw new IllegalStateException("Legal content or public context changed without a new document revision.");
            }
            sql.update("INSERT INTO legal_document_heads(slug,version) VALUES(?,?) ON CONFLICT(slug) DO UPDATE SET version=excluded.version,updated_at=now()",doc.slug(),doc.version());
            audit.record(null,"legal_document_published","legal_document",null,Map.of("slug",doc.slug(),"version",doc.version(),"hash",doc.hash(),"revision",doc.revision()));
        }
    }
    /** Separate transaction: publication locks never wait for participant/user locks through notification FKs. */
    @Transactional public void notifyChanges(){
        var documents=sql.queryForList("SELECT v.slug,v.version,v.title,v.change_summary FROM legal_document_heads h JOIN legal_document_versions v ON v.slug=h.slug AND v.version=h.version WHERE v.effective_at<=now() ORDER BY v.slug");
        for(var doc:documents){
            var owners=sql.query("INSERT INTO legal_change_notices(user_id,document_slug,document_version) SELECT u.id,?,? FROM users u WHERE u.deleted_at IS NULL AND u.account_status<>'deleted' AND u.email_verified_at IS NOT NULL AND (?<>'driver-terms' OR EXISTS(SELECT 1 FROM driver_profiles d WHERE d.user_id=u.id AND d.status IN ('pending','approved'))) AND NOT EXISTS(SELECT 1 FROM legal_acceptances a WHERE a.user_id=u.id AND a.document_slug=? AND a.document_version=?) ORDER BY u.id ON CONFLICT DO NOTHING RETURNING user_id",(r,n)->r.getObject(1,UUID.class),doc.get("slug"),doc.get("version"),doc.get("slug"),doc.get("slug"),doc.get("version"));
            for(var owner:owners)sql.update("INSERT INTO notifications(user_id,title,body,route) VALUES(?,'Documentos do UniCarona atualizados',?,'settings')",owner,doc.get("title")+": "+doc.get("change_summary"));
        }
    }
}

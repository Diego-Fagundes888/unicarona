package com.unicarona.reservation.service;

import com.unicarona.reservation.dto.*;
import com.unicarona.reservation.repository.ReservationReadRepository;
import com.unicarona.security.Actor;
import com.unicarona.shared.*;
import com.unicarona.shared.dto.PageDto;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @Transactional(readOnly=true)
public class ReservationReadService {
    private final ReservationReadRepository reads;
    public ReservationReadService(ReservationReadRepository reads) { this.reads=reads; }
    public PageDto<ReservationDto.Item> list(Actor actor,ReservationQueries query) {
        var page=query.page();return PageDto.from(reads.list(actor.id(),query.side(),query.rideId(),query.contractId(),query.upcoming(),page.limit()+1,page.offset(),null),page.limit(),page.offset());
    }
    public ReservationDto.Item detail(Actor actor,String raw) {
        UUID id=UUID.fromString(raw);String side=reads.ownedSide(actor.id(),id).orElseThrow(ReservationReadService::missing);
        // Compare canonical UUIDs: the original SQL found uppercase IDs but its later string comparison lost them.
        return reads.list(actor.id(),side,null,null,false,1,0,id).stream().findFirst().orElseThrow(ReservationReadService::missing);
    }
    public ReservationDto.Contact contact(Actor actor,String id) { return reads.contact(actor.id(),UUID.fromString(id)).orElseThrow(() -> new ApiException("CONTACT_UNAVAILABLE","Contato indisponível.",404)); }
    private static ApiException missing() { return new ApiException("RESERVATION_NOT_FOUND","Reserva não encontrada.",404); }
}

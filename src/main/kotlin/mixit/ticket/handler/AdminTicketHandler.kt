package mixit.ticket.handler

import kotlinx.coroutines.reactor.awaitSingleOrNull
import mixit.MixitProperties
import mixit.routes.MustacheI18n.CREATION_MODE
import mixit.routes.MustacheI18n.MESSAGE
import mixit.routes.MustacheI18n.TICKET
import mixit.routes.MustacheI18n.TICKETS
import mixit.routes.MustacheI18n.TITLE
import mixit.routes.MustacheI18n.TYPES
import mixit.routes.MustacheTemplate.AdminTicket
import mixit.routes.MustacheTemplate.AdminTicketPrint
import mixit.routes.MustacheTemplate.TicketError
import mixit.security.MixitWebFilter
import mixit.security.model.Cryptographer
import mixit.ticket.model.Ticket
import mixit.ticket.model.TicketService
import mixit.ticket.model.TicketType
import mixit.user.model.Role
import mixit.util.enumMatcher
import mixit.util.errors.NotFoundException
import mixit.util.extractFormData
import mixit.util.json
import mixit.util.seeOther
import mixit.util.webSession
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.ServerResponse.ok
import org.springframework.web.reactive.function.server.bodyValueAndAwait
import org.springframework.web.reactive.function.server.renderAndAwait
import reactor.core.publisher.Mono
import java.time.Instant

@Component
class AdminTicketHandler(
    private val service: TicketService,
    private val properties: MixitProperties,
    private val cryptographer: Cryptographer
) {

    companion object {
        const val LIST_URI = "/admin/ticket"
    }

    suspend fun findAll(req: ServerRequest): ServerResponse {
        val tickets = service.findAll().map { it.toEntity(cryptographer) }
        return ok().json().bodyValueAndAwait(tickets)
    }

    suspend fun ticketing(req: ServerRequest): ServerResponse {
        val tickets = service.findAll().map { it.toDto(cryptographer) }
        val params = mapOf(
            TITLE to AdminTicket.title,
            TICKETS to tickets
        )
        val template = if (properties.feature.lotteryResult) AdminTicket.template else throw NotFoundException()
        return ok().renderAndAwait(template, params)
    }

    suspend fun printTicketing(req: ServerRequest): ServerResponse {
        val tickets = service.findAll().map { ticket ->
            ticket.toDto(cryptographer)
                .copy(
                    firstname = ticket.firstname?.uppercase() ?: ticket.lastname.uppercase(),
                    lastname = if (ticket.firstname == null) "" else ticket.lastname.uppercase()
                )
        }
        val template =
            if (properties.feature.lotteryResult) AdminTicketPrint.template else throw NotFoundException()
        val params = mapOf(
            TITLE to "admin.ticket.title",
            TICKETS to tickets
        )
        return ok().renderAndAwait(template, params)
    }

    suspend fun createTicket(req: ServerRequest): ServerResponse =
        this.adminTicket()

    suspend fun editTicket(req: ServerRequest): ServerResponse =
        this.adminTicket(
            service
                .findOneOrNull(req.pathVariable("number"))
                ?.toEntity(cryptographer)
                ?: throw NotFoundException()
        )

    private suspend fun adminTicket(ticket: Ticket = Ticket.empty(cryptographer)): ServerResponse {
        val template =
            if (properties.feature.lotteryResult) AdminTicketPrint.template else throw NotFoundException()
        val params = mapOf(
            TITLE to AdminTicketPrint.title,
            CREATION_MODE to ticket.encryptedEmail.isEmpty(),
            TICKET to ticket,
            TYPES to enumMatcher(ticket) { ticket.type }
        )
        return ok().renderAndAwait(template, params)
    }

    suspend fun submit(req: ServerRequest): ServerResponse {
        val formData = req.extractFormData()
        val existingTicket = service.findByNumber(formData["number"]!!)
        val ticket = existingTicket
            ?.toEntity(cryptographer)
            ?.copy(
                encryptedEmail = cryptographer.encrypt(formData["email"]!!.lowercase())!!,
                firstname = cryptographer.encrypt(formData["firstname"]),
                lastname = cryptographer.encrypt(formData["lastname"])!!,
                externalId = cryptographer.encrypt(formData["externalId"]),
                type = TicketType.valueOf(formData["type"]!!),
            )
            ?: Ticket(
                number = cryptographer.encrypt(formData["number"])!!,
                encryptedEmail = cryptographer.encrypt(formData["email"]!!.lowercase())!!,
                firstname = cryptographer.encrypt(formData["firstname"]),
                lastname = cryptographer.encrypt(formData["lastname"])!!,
                externalId = cryptographer.encrypt(formData["externalId"]),
                lotteryRank = formData["lotteryRank"]?.toInt(),
                createdAt = Instant.parse(formData["createdAt"])!!,
                type = TicketType.valueOf(formData["type"]!!),
            )

        val params = mapOf(
            TITLE to TicketError.title,
            MESSAGE to "admin.ticket.error.alreadyexists"
        )

        val result = service.save(ticket)
            .onErrorResume(DuplicateKeyException::class.java) { Mono.empty() }
            .awaitSingleOrNull()

        return result?.let { seeOther("${properties.baseUri}$LIST_URI") } ?: ok().renderAndAwait(
            TicketError.template,
            params
        )
    }

    suspend fun adminDeleteTicket(req: ServerRequest): ServerResponse {
        val formData = req.extractFormData()
        service.deleteOne(formData["number"]!!).awaitSingleOrNull()
        return seeOther("${properties.baseUri}$LIST_URI")
    }

    suspend fun showAttendee(req: ServerRequest): ServerResponse {
        val attendee = service.findByNumber(req.pathVariable("number"))
            ?: return seeOther("${properties.baseUri}/")

        val session = req.webSession()
        return when (session.getAttribute<Role>(MixitWebFilter.SESSION_ROLE_KEY)) {
            Role.STAFF -> {
                // A staff member is redirected to Mixette form
                seeOther("${properties.baseUri}/admin/mixette-donation/create/${attendee.number}")
            }

            Role.VOLUNTEER -> {
                // A staff member is redirected to Mixette form
                seeOther("${properties.baseUri}/volunteer/mixette-donation/create/${attendee.number}")
            }

            else -> {
                // Other members could be redirected to user profile in the future
                seeOther("${properties.baseUri}/")
            }
        }
    }
}

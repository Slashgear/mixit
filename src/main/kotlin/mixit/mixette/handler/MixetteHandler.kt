package mixit.mixette.handler

import mixit.MixitApplication.Companion.CURRENT_EVENT
import mixit.MixitApplication.Companion.TIMEZONE
import mixit.MixitProperties
import mixit.event.model.EventService
import mixit.mixette.repository.MixetteDonationRepository
import mixit.routes.MustacheI18n.TITLE
import mixit.routes.MustacheTemplate.MixetteDashboard
import mixit.util.frenchTalkTimeFormatter
import mixit.util.language
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.ServerResponse.ok
import org.springframework.web.reactive.function.server.bodyValueAndAwait
import org.springframework.web.reactive.function.server.renderAndAwait
import java.time.LocalTime
import java.time.ZoneId

@Component
class MixetteHandler(
    private val repository: MixetteDonationRepository,
    private val eventService: EventService,
    private val properties: MixitProperties
) {

    suspend fun mixette(req: ServerRequest): ServerResponse {
        val organizations = eventService.findByYear(CURRENT_EVENT).organizations
        val donationByOrgas = repository.findAllByYear(CURRENT_EVENT)
            .groupBy { donation ->
                organizations.first { it.login == donation.organizationLogin }.let {
                    MixetteOrganizationDonationDto(name = it.company, login = it.login)
                }
            }
            .map { entry ->
                entry.key.populate(
                    number = entry.value.size,
                    quantity = entry.value.sumOf { it.quantity },
                    amount = entry.value.sumOf { it.quantity * properties.mixetteValue.toDouble() }
                )
            }
        val params = mapOf(
            "organizations" to (organizations.map { it.toSponsorDto(req.language()) }),
            "donations" to donationByOrgas,
            "loadAt" to LocalTime.now(ZoneId.of(TIMEZONE)).format(frenchTalkTimeFormatter),
            TITLE to MixetteDashboard.title
        )

        return ok().renderAndAwait(MixetteDashboard.template, params)
    }

    suspend fun mixetteRealTime(req: ServerRequest): ServerResponse =
        ok()
            .contentType(MediaType.TEXT_EVENT_STREAM)
            .bodyValueAndAwait(repository.findByYearAfterNow(CURRENT_EVENT))
}

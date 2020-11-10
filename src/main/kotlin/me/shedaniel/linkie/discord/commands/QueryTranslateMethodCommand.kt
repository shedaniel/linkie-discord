package me.shedaniel.linkie.discord.commands

import discord4j.core.`object`.entity.Message
import discord4j.core.`object`.entity.User
import discord4j.core.`object`.entity.channel.MessageChannel
import discord4j.core.event.domain.message.MessageCreateEvent
import discord4j.core.spec.EmbedCreateSpec
import me.shedaniel.linkie.*
import me.shedaniel.linkie.discord.*
import me.shedaniel.linkie.discord.utils.*
import me.shedaniel.linkie.utils.dropAndTake
import me.shedaniel.linkie.utils.onlyClass
import me.shedaniel.linkie.utils.remapFieldDescriptor
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.ceil

class QueryTranslateMethodCommand(private val source: Namespace, private val target: Namespace) : CommandBase {
    override fun execute(event: MessageCreateEvent, prefix: String, user: User, cmd: String, args: MutableList<String>, channel: MessageChannel) {
        source.validateNamespace()
        source.validateGuild(event)
        target.validateNamespace()
        target.validateGuild(event)
        args.validateUsage(prefix, 1..2, "$cmd <search> [version]")
        val sourceMappingsProvider = if (args.size == 1) MappingsProvider.empty(source) else source.getProvider(args.last())
        val allVersions = source.getAllSortedVersions().toMutableList()
        allVersions.retainAll(target.getAllSortedVersions())
        if (sourceMappingsProvider.isEmpty() && args.size == 2) {
            throw NullPointerException(
                "Invalid Version: " + args.last() + "\nVersions: " +
                        if (allVersions.size > 20)
                            allVersions.take(20).joinToString(", ") + ", etc"
                        else allVersions.joinToString(", ")
            )
        }
        sourceMappingsProvider.injectDefaultVersion(source.getProvider(allVersions.first()))
        sourceMappingsProvider.validateDefaultVersionNotEmpty()
        val targetMappingsProvider = target.getProvider(sourceMappingsProvider.version!!)
        if (targetMappingsProvider.isEmpty()) {
            throw NullPointerException(
                "Invalid Version: " + args.last() + "\nVersions: " +
                        if (allVersions.size > 20)
                            allVersions.take(20).joinToString(", ") + ", etc"
                        else allVersions.joinToString(", ")
            )
        }
        require(!args.first().replace('.', '/').contains('/')) { "Query with classes are not available with translating queries." }
        val searchTerm = args.first().replace('.', '/').onlyClass()
        val sourceVersion = sourceMappingsProvider.version!!
        val targetVersion = targetMappingsProvider.version!!
        val message = AtomicReference<Message?>()
        var page = 0
        val maxPage = AtomicInteger(-1)
        val remappedMethods = ValueKeeper(Duration.ofMinutes(2)) { build(searchTerm, source.getProvider(sourceVersion), target.getProvider(targetVersion), user, message, channel, maxPage) }
        message.editOrCreate(channel) { buildMessage(remappedMethods.get(), sourceVersion, page, user, maxPage.get()) }.subscribe { msg ->
            msg.tryRemoveAllReactions().block()
            buildReactions(remappedMethods.timeToKeep) {
                if (maxPage.get() > 1) register("⬅") {
                    if (page > 0) {
                        page--
                        message.editOrCreate(channel) { buildMessage(remappedMethods.get(), sourceVersion, page, user, maxPage.get()) }.subscribe()
                    }
                }
                registerB("❌") {
                    msg.delete().subscribe()
                    false
                }
                if (maxPage.get() > 1) register("➡") {
                    if (page < maxPage.get() - 1) {
                        page++
                        message.editOrCreate(channel) { buildMessage(remappedMethods.get(), sourceVersion, page, user, maxPage.get()) }.subscribe()
                    }
                }
            }.build(msg, user)
        }
    }

    private fun build(
        searchTerm: String,
        sourceProvider: MappingsProvider,
        targetProvider: MappingsProvider,
        user: User,
        message: AtomicReference<Message?>,
        channel: MessageChannel,
        maxPage: AtomicInteger,
    ): MutableMap<MethodCompound, String> {
        if (!sourceProvider.cached!!) message.editOrCreate(channel) {
            setFooter("Requested by " + user.discriminatedName, user.avatarUrl)
            setTimestampToNow()
            var desc = "Searching up methods for **${sourceProvider.namespace.id} ${sourceProvider.version}**.\nIf you are stuck with this message, please do the command again."
            if (!sourceProvider.cached!!) desc += "\nThis mappings version is not yet cached, might take some time to download."
            setDescription(desc)
        }.block()
        else if (!targetProvider.cached!!) message.editOrCreate(channel) {
            setFooter("Requested by " + user.discriminatedName, user.avatarUrl)
            setTimestampToNow()
            var desc = "Searching up methods for **${targetProvider.namespace.id} ${targetProvider.version}**.\nIf you are stuck with this message, please do the command again."
            if (!targetProvider.cached!!) desc += "\nThis mappings version is not yet cached, might take some time to download."
            setDescription(desc)
        }.block()
        return getCatching(message, channel, user) {
            val sourceMappings = sourceProvider.mappingsContainer!!.invoke()
            val targetMappings = targetProvider.mappingsContainer!!.invoke()
            val remappedMethods = mutableMapOf<MethodCompound, String>()
            sourceMappings.classes.forEach { sourceClassParent ->
                sourceClassParent.methods.forEach inner@{ sourceMethod ->
                    if (sourceMethod.intermediaryName.onlyClass().equals(searchTerm, true) || sourceMethod.mappedName?.onlyClass()?.equals(searchTerm, true) == true) {
                        val obfName = sourceMethod.obfName.merged!!
                        val obfDesc = sourceMethod.obfDesc.merged!!
                        val parentObfName = sourceClassParent.obfName.merged!!
                        val targetClass = targetMappings.getClassByObfName(parentObfName) ?: return@inner
                        val targetMethod = targetClass.methods.firstOrNull { it.obfName.merged == obfName && it.obfDesc.merged == obfDesc } ?: return@inner
                        remappedMethods[MethodCompound(
                            sourceClassParent.optimumName.onlyClass() + "#" + sourceMethod.optimumName,
                            sourceMethod.obfDesc.merged ?: sourceMethod.intermediaryDesc.remapFieldDescriptor {
                                sourceMappings.getClass(it)?.obfName?.merged ?: it
                            }
                        )] =
                            targetClass.optimumName.onlyClass() + "#" + targetMethod.optimumName
                    }
                }
            }
            if (remappedMethods.isEmpty()) {
                if (!searchTerm.isValidIdentifier()) {
                    throw NullPointerException("No results found! `$searchTerm` is not a valid java identifier!")
                } else if (searchTerm.startsWith("field_")) {
                    throw NullPointerException("No results found! `$searchTerm` looks like a field!")
                } else if (searchTerm.startsWith("class_")) {
                    throw NullPointerException("No results found! `$searchTerm` looks like a class!")
                }
                throw NullPointerException("No results found!")
            }

            maxPage.set(ceil(remappedMethods.size / 5.0).toInt())
            return@getCatching remappedMethods
        }
    }

    private data class MethodCompound(
        val optimumName: String,
        val obfDesc: String,
    )

    private fun EmbedCreateSpec.buildMessage(remappedMethods: MutableMap<MethodCompound, String>, version: String, page: Int, author: User, maxPage: Int) {
        setFooter("Requested by " + author.discriminatedName, author.avatarUrl)
        setTimestampToNow()
        if (maxPage > 1) setTitle("List of ${source.id.capitalize()}->${target.id.capitalize()} Mappings (Page ${page + 1}/$maxPage)")
        else setTitle("List of ${source.id.capitalize()}->${target.id.capitalize()} Mappings")
        var desc = ""
        remappedMethods.entries.dropAndTake(5 * page, 5).forEach { (original, remapped) ->
            if (desc.isNotEmpty())
                desc += "\n"
            desc += "**MC $version: ${original.optimumName} => `$remapped`**\n"
        }
        setSafeDescription(desc)
    }

    override fun getName(): String = "${source.id.capitalize()}->${target.id.capitalize()} Method Command"
    override fun getDescription(): String = "Query ${source.id}->${target.id} methods."
}
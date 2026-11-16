// Port of Tests/MoveBoundaryChecks.swift
// Copyright (c) 2026 Salvatore Sanfilippo (Swift original), YouRoll contributors (Kotlin port). MIT License.
package com.salvozappa.youroll

fun moveBoundaryChecks() {
    fun check(ok: Boolean, message: String) = require(ok, message)
    val source = SourceMedia(filename = "moves.mov", duration = 18.0)
    val scenes = (0 until 3).map { TimelineClip(sourceID = source.id, start = (6 * it).toDouble(), end = (6 * (it + 1)).toDouble()) }
    val base = TrackTimeline(scenes)
    val picked = base.selection(of = scenes[1].id)
    val choices = base.moveChoices(picked)
    fun stops(t: TrackTimeline, moved: TimelineSelection) = TimelineMoveBoundaries(timeline = t, picked = picked, moving = moved)
    val plain = stops(base, picked)
    check(plain.anchor == et(6) && plain.times == listOf(0, 6, 12, 18).map { et(it) }, "Aligned picture and sound stops must occur exactly once")
    check(plain.next(et(6)) == et(12) && plain.previous(et(12)) == et(6) && plain.next(et(18)) == null && plain.previous(zero) == null,
        "Strict, reversible stops and finite endpoints")
    val left = stops(base, choices.left)
    val right = stops(base, choices.right)
    check(left.anchor == plain.anchor && left.previous(plain.anchor) == null && right.times == listOf(0, 6, 18).map { et(it) },
        "Companions keep the picked anchor, skip internal cuts and cannot move before zero")
    for (scope in listOf(choices.selected, choices.left, choices.right, choices.both)) {
        val plan = stops(base, scope)
        for (target in plan.times) {
            val draft = base.copy(); draft.move(scope, target - plan.anchor)
            for (item in base.video) {
                val delta = if (item.id in scope.video) target - plan.anchor else zero
                check(draft.video.firstOrNull { it.id == item.id }?.start == item.start + delta, "Move must preserve all picture offsets and untouched clips")
            }
            for (item in base.audio) {
                val delta = if (item.id in scope.audio) target - plan.anchor else zero
                check(draft.audio.firstOrNull { it.id == item.id }?.start == item.start + delta, "Move must preserve linked sync")
            }
            draft.validate(listOf(source))
        }
    }
    val gaps = base.copy()
    gaps.move(gaps.selection(of = scenes[2].id), EditTime(1))
    val first = gaps.selection(of = scenes[0].id)
    val tickStops = TimelineMoveBoundaries(timeline = gaps, picked = first, moving = first)
    val tick = et(12) + EditTime(1)
    check(tickStops.next(et(12)) == tick && tickStops.previous(tick) == et(12), "A one-tick gap exposes two independently reachable edges")
    gaps.unlink(gaps.selection(of = scenes[2].id))
    val loose = gaps.selection(of = gaps.audio[2].id)
    gaps.move(loose, et(1))
    val audioCuts = TimelineMoveBoundaries(timeline = gaps, picked = picked, moving = picked)
    check(tick + et(1) in audioCuts.times, "An independent sound edit is a valid destination for picture")
    val anchoredAudio = TimelineMoveBoundaries(timeline = gaps, picked = loose, moving = loose)
    val audioDraft = gaps.copy(); audioDraft.move(loose, zero - anchoredAudio.anchor)
    check(audioDraft.video == gaps.video && audioDraft.audio[2].start == zero, "Detached audio can be precisely placed without moving picture")

    val blend = EditState(base).settingTransition(ClipTransition(duration = 1.0), 1)
    val last = blend.timeline.selection(of = scenes[2].id)
    val folded = TimelineMoveBoundaries(timeline = blend.timeline, picked = last, moving = last)
    val join = blend.layout.joins.first { it.transition != null }
    check(EditTime.of(join.time) in folded.times && blend.timeline.video[1].start !in folded.times &&
        blend.timeline.video[0].end !in folded.times, "Dissolves offer their displayed join instead of their underlying overlap handles")

    val scoped = base.scoped(base.entireSelection(iv(1.25, 3.25)))
    val partial = TimelineMoveBoundaries(timeline = scoped.timeline, picked = scoped.selection, moving = scoped.selection)
    check(partial.anchor == et(1.25) && partial.next(et(1.25)) == et(3.25), "A marked range aligns from the chosen source fragment")
    val separate = base.selection(ids = setOf(scenes[0].id, scenes[2].id))
    val grouped = base.copy()
    grouped.move(separate, et(6))
    check(grouped.video.map { it.start } == listOf(6, 6, 18).map { et(it) }, "Disjoint selected parts move together, preserving their gap")
}

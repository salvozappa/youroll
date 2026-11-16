// YouRoll JVM tests for the Video effects workspace (VideoEffectsEditor.kt): adding,
// animating with keyframes, the Freeze Frame hold, Color Correction A/B and removal.
// Copyright (c) 2026 Salvatore Sanfilippo (Swift original), YouRoll contributors (Kotlin port). MIT License.
package com.salvozappa.youroll

import org.junit.Test
import kotlin.math.abs

class VideoEffectsEditorTests {
    private fun project(): Project = Project("Effects").also { it.append(SourceMedia(filename = "source.mp4", duration = 8.0)) }
    private fun workspace(project: Project, position: Double) =
        VideoEffectsWorkspace(project, java.io.File("."), ToolRequest(VideoTool.videoEffects, 0.0..project.edit.duration, "Whole project"), position)

    @Test fun addAnimateFreezeAndRemove() {
        val project = project()
        var model = workspace(project, 5.0)
        model.add(VideoEffectID.silentTint)
        val tint = model.current!!
        require(tint.kind == VideoEffectID.silentTint && model.state.clips[0].videoEffects!!.single().start == 0.0, "Adding covers the scoped clip")
        model.animate = true
        model.set(tint.parameters.first { it.id == "intensity" }, EffectValue.number(0.2))
        val keyed = model.current!!.parameters.first { it.id == "intensity" }
        require(keyed.keyframes.map { it.time } == listOf(0.0, 5.0), "Animating keys the clip start and the playhead: ${keyed.keyframes.map { it.time }}")
        require(abs(model.current!!.number("intensity", 2.5) - 0.6) < 1e-9, "Keyframes interpolate")
        model.animate = false
        model.set(model.current!!.parameters.first { it.id == "intensity" }, EffectValue.number(0.4))
        require(model.current!!.parameters.first { it.id == "intensity" }.keyframes.last().value == EffectValue.number(0.4), "An animated control edits its key at the playhead")
        model.keyframe(model.current!!.parameters.first { it.id == "intensity" })
        require(model.current!!.parameters.first { it.id == "intensity" }.keyframes.size == 1, "The diamond removes the key at the playhead")

        model = workspace(project, 2.0)
        model.add(VideoEffectID.freezeFrame)
        require(abs(model.state.duration - 11.0) < 1e-9 && model.current?.kind == VideoEffectID.freezeFrame, "Freeze Frame inserts a three-second hold")
        model.set(model.current!!.parameters.first { it.id == "hold" }, EffectValue.number(5.0))
        require(abs(model.state.duration - 13.0) < 1e-9, "Hold duration retimes the hold")
        model.remove()
        require(abs(model.state.duration - 8.0) < 1e-9 && model.state.clips.none { it.frozenFrame != null }, "Removing the Freeze Frame removes its hold")

        model = workspace(project, 1.0)
        model.add(VideoEffectID.crashZoom)
        require(!model.current!!.toggle("trackFace") && VideoEffectSupport.unsupported(model.current!!) == null, "A new Crash Zoom does not ask for face tracking")
        model.add(VideoEffectID.colorCorrection)
        model.selectCorrection(EffectEndpoint.b)
        model.set(model.current!!.parameters.first { it.id == "exposure" }, EffectValue.number(1.0))
        val motion = model.current!!.motion!!
        require(motion.b["exposure"] == 1.0 && motion.a["exposure"] == 0.0 && model.current!!.valid, "Editing B changes only the B pose")
        model.updateCorrectionTiming(EffectTiming(EffectCurve.riseAndHold, 2.0, 1.0))
        require(model.current!!.motion!!.timing.curve == EffectCurve.riseAndHold && abs(model.current!!.number("exposure", 2.0) - 1.0) < 1e-9, "Rise & hold reaches B")
        model.removeCorrectionAnimation()
        require(model.current!!.motion == null && model.current!!.number("exposure") == 1.0, "Removing the animation keeps the edited endpoint")
        // After a freeze, animating keys the clip at the playhead from its own start.
        model = workspace(project, 1.7)
        model.add(VideoEffectID.silentTint)
        model.add(VideoEffectID.freezeFrame)
        val frozen = model.state
        model = workspace(Project("Frozen").also { p -> p.append(SourceMedia(id = project.sources[0].id, filename = "source.mp4", duration = 8.0)); p.commit(frozen) }, 5.2)
        model.selected = model.active.first { it.kind == VideoEffectID.silentTint }.id
        model.animate = true
        model.set(model.current!!.parameters.first { it.id == "intensity" }, EffectValue.number(0.2))
        val times = model.current!!.parameters.first { it.id == "intensity" }.keyframes.map { it.time }
        require(times.size == 2 && abs(times[0] - 1.7) < 1e-9 && abs(times[1] - 2.2) < 1e-6, "Keys at the clip start and the playhead after a hold: $times")
        val result = model.result()
        require(result.clips.count { clip -> clip.videoEffects.orEmpty().any { it.kind == VideoEffectID.silentTint } } == 3, "The result keeps the look on every piece")
    }
}

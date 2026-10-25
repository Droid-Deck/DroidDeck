// DroidDeck cloud player page: runs one Xbox Cloud Gaming session. Every request to Microsoft goes
// through the app (DDCloud), which holds the tokens; this page only does WebRTC, video, sound and input.
import xCloudPlayer from './greenlight/lib/player'
import Gamepad from './greenlight/lib/input/gamepad'
import { EffectRenderer, Effects, needsRenderer } from './renderer'
import { StreamStats } from './streamstats'
import { capVideoBitrate, preferH264Profile } from './sdp-tweaks'

interface Reply { status: number, body: string }
declare const DDCloud: { call(id: number, op: string, arg: string): void, status(text: string): void, failed(stage: string, detail: string): void, playing(): void, log(text: string): void, settings(): string }

/** The player settings the app hands over (XboxCloudSettings.toJson); stream-start ones are applied by the app. */
interface Settings extends Effects {
    bitrateMbps: number      // 0 = Microsoft's own limit
    codec: string            // 'default' | 'high' | 'normal' | 'low'
    preventDrops: boolean
    statsLevel: number       // 0 off, 1 fps, 2 basic, 3 detailed, 4 full
    statsLayout: string      // 'horizontal' | 'vertical'
    statsCorner: string      // 'right' | 'left'
    server: string           // the region's host, for the full overlay
    volume: number           // 100..300 %
    rumble: number           // 0..100 %
}
let settings: Settings = JSON.parse(DDCloud.settings())

// Codec profile and bitrate cap go into our offer (Better xCloud's approach).
const nativeSetLocalDescription = RTCPeerConnection.prototype.setLocalDescription
RTCPeerConnection.prototype.setLocalDescription = function (this: RTCPeerConnection, description?: RTCLocalSessionDescriptionInit) {
    if (description?.sdp) {
        if (settings.codec === 'high' || settings.codec === 'normal' || settings.codec === 'low') description.sdp = preferH264Profile(description.sdp, settings.codec)
        if (settings.bitrateMbps > 0) description.sdp = capVideoBitrate(description.sdp, settings.bitrateMbps * 1000)
    }
    return nativeSetLocalDescription.call(this, description as RTCLocalSessionDescriptionInit)
} as typeof RTCPeerConnection.prototype.setLocalDescription
;(window as any).__ddRumble = settings.rumble / 100

// Page errors go to the app's Xbox log (no tokens ever reach this page).
window.addEventListener('error', event => DDCloud.log(`error: ${event.message} @${event.lineno}`))
window.addEventListener('unhandledrejection', event => DDCloud.log(`rejection: ${String(event.reason).slice(0, 200)}`))
window.addEventListener('gamepadconnected', event => DDCloud.log(`controller: ${event.gamepad.id.slice(0, 60)} mapping=${event.gamepad.mapping || 'none'} buttons=${event.gamepad.buttons.length} axes=${event.gamepad.axes.length}`))

const pending = new Map<number, (reply: Reply) => void>()
let nextId = 1
;(window as any).__ddResolve = (id: number, status: number, body: string) => {
    const done = pending.get(id)
    pending.delete(id)
    if (done) done({ status, body })
}

function call(op: string, arg: unknown = ''): Promise<Reply> {
    return new Promise(resolve => {
        const id = nextId++
        pending.set(id, resolve)
        DDCloud.call(id, op, typeof arg === 'string' ? arg : JSON.stringify(arg))
    })
}

const sleep = (ms: number) => new Promise(resolve => setTimeout(resolve, ms))
const ok = (reply: Reply) => reply.status >= 200 && reply.status < 300

function show(text: string) {
    const element = document.getElementById('status')
    if (element) element.textContent = text
    DDCloud.status(text)
}

class Failure extends Error {
    constructor(public stage: string, public detail: string) { super(stage) }
}

function expect(stage: string, reply: Reply): Reply {
    if (!ok(reply)) throw new Failure(stage, `${reply.status} ${reply.body.slice(0, 300)}`)
    return reply
}

function waitForCandidates(pc: RTCPeerConnection, timeout: number): Promise<void> {
    return new Promise(resolve => {
        if (pc.iceGatheringState === 'complete') return resolve()
        const timer = setTimeout(resolve, timeout)
        pc.addEventListener('icegatheringstatechange', () => {
            if (pc.iceGatheringState === 'complete') { clearTimeout(timer); resolve() }
        })
    })
}

async function run() {
    show('Starting the game on Xbox Cloud Gaming…')
    expect('start', await call('start'))

    let connected = false
    let waited = 0
    for (;;) {
        const reply = expect('state', await call('state'))
        const state = JSON.parse(reply.body || '{}')
        if (state.state === 'Provisioned') break
        if (state.state === 'Failed') throw new Failure('provision', JSON.stringify(state.errorDetails ?? {}).slice(0, 300))
        if (state.state === 'ReadyToConnect' && !connected) {
            connected = true
            show('Signing in to the cloud console…')
            expect('connect', await call('connect'))
        } else if (state.state === 'WaitingForResources') {
            show(`In the queue for a cloud console… ${Math.round(waited / 1000)} s`)
        } else {
            show(`Getting a cloud console ready… ${Math.round(waited / 1000)} s`)
        }
        await sleep(1000)
        waited += 1000
    }

    show('Connecting…')
    const player = new xCloudPlayer('player')
    player.init()
    const pc = player._peerConnection

    // "Stop quality drops": report a short decode time so the server does not lower the resolution
    // for a slow decoder (Better xCloud's patchStreamMetadata).
    const input = player._channels.input as any
    const queueMetadata = input.queueMetadataFrame.bind(input)
    input.queueMetadataFrame = (frame: any) => {
        if (settings.preventDrops) {
            const render = frame.frameRenderedTimeMs - frame.frameDecodedTimeMs
            frame.frameDecodedTimeMs = frame.frameSubmittedTimeMs + 10
            frame.frameRenderedTimeMs = frame.frameDecodedTimeMs + render
        }
        queueMetadata(frame)
    }

    // Volume above 100 %: the stream's sound goes through a gain node instead of the <audio> element.
    let gain: GainNode | undefined
    let audioStream: MediaStream | undefined
    const applyVolume = () => {
        if (audioStream === undefined || (gain === undefined && settings.volume === 100)) return
        if (gain === undefined) {
            const context = new AudioContext()
            gain = context.createGain()
            context.createMediaStreamSource(audioStream).connect(gain).connect(context.destination)
            const element = document.querySelector('#player audio') as HTMLAudioElement | null
            if (element) element.muted = true
        }
        gain.gain.value = settings.volume / 100
    }
    pc.addEventListener('track', event => {
        if (event.track.kind === 'audio') { audioStream = event.streams[0]; applyVolume() }
    })

    const stats = new StreamStats(pc)
    let renderer: EffectRenderer | undefined
    const applyEffects = () => {
        const video = player.getVideoElement() as HTMLVideoElement | undefined
        if (video === undefined) return
        if (!needsRenderer(settings)) { renderer?.pause(); return }
        if (renderer === undefined) {
            try { renderer = new EffectRenderer(video, settings) }
            catch (error) { DDCloud.log(`effects unavailable: ${String(error).slice(0, 200)}`) }
        } else renderer.resume(settings)
    }
    // Live changes from the in-player menu.
    ;(window as any).__ddApply = (json: string) => {
        settings = JSON.parse(json)
        ;(window as any).__ddRumble = settings.rumble / 100
        stats.show(settings.statsLevel, settings.statsLayout === 'vertical', settings.server, settings.statsCorner === 'left')
        applyVolume()
        applyEffects()
    }
    pc.addEventListener('connectionstatechange', () => {
        if (pc.connectionState === 'connected') {
            show(''); DDCloud.playing()
            stats.show(settings.statsLevel, settings.statsLayout === 'vertical', settings.server, settings.statsCorner === 'left')
            // The video element exists once its track arrived; give it a moment for its first frame.
            setTimeout(applyEffects, 500)
        }
        else if (pc.connectionState === 'failed') DDCloud.failed('webrtc', 'connection failed')
        else if (pc.connectionState === 'disconnected') show('Connection lost…')
    })

    const offer = await player.createOffer()
    const sdp = expect('sdp', await call('sdp', offer.sdp ?? ''))
    const answer = JSON.parse(JSON.parse(sdp.body).exchangeResponse)
    if (answer.status && answer.status !== 'success') throw new Failure('sdp-answer', JSON.stringify(answer).slice(0, 300))
    await player.setRemoteSDP(answer.sdp)

    await waitForCandidates(pc, 3000)
    const local = (await player.getICECandidates()).map(c => JSON.stringify({
        candidate: c.candidate, sdpMid: c.sdpMid, sdpMLineIndex: c.sdpMLineIndex, usernameFragment: c.usernameFragment,
    }))
    const ice = expect('ice', await call('ice', local))
    player.setRemoteIceCandidates(JSON.parse(JSON.parse(ice.body).exchangeResponse))

    // The control channel's authorization (sent on the message handshake) announces controller 0 and
    // then removes it, clearing any handler attached before; attach the controller after that.
    const handshake = Date.now()
    while (!player._channels.message._handshakeCompleted && Date.now() - handshake < 20_000) await sleep(100)
    DDCloud.log(`handshake ${player._channels.message._handshakeCompleted ? 'done' : 'missing'} after ${Date.now() - handshake} ms`)
    await sleep(300)
    const pad = new Gamepad(0, { enable_keyboard: false })
    pad.attach(player)
    DDCloud.log(`controllers at attach: ${Array.from(navigator.getGamepads()).filter(Boolean).length}`)
    // The app's "Xbox button" (in the leave dialog) presses Nexus for a moment.
    ;(window as any).__ddNexus = () => { pad.sendButtonState('Nexus', 1); setTimeout(() => pad.sendButtonState('Nexus', 0), 200) }
    // The app's menu button / a controller's Guide button: Nexus follows the physical button.
    ;(window as any).__ddButton = (name: string, value: number) => pad.sendButtonState(name, value)
    // The in-player menu pauses controller input to the game while it is open.
    ;(window as any).__ddInput = (enabled: boolean) => { if (enabled) pad.onWindowFocus(); else pad.onWindowBlur() }

    setInterval(() => { call('keepalive') }, 30_000)
    ;(window as any).__ddStop = () => { try { renderer?.destroy(); stats.destroy(); pad.detach(); player.destroy() } catch (e) { /* closing anyway */ } }
}

run().catch((error: unknown) => {
    if (error instanceof Failure) DDCloud.failed(error.stage, error.detail)
    else DDCloud.failed('page', String(error).slice(0, 300))
})

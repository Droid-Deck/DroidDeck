// Performance overlay in the top-right or top-left corner, from WebRTC getStats. Levels: 1 fps; 2 + ping, resolution;
// 3 + bitrate, decode time, packet loss; 4 + codec, jitter, dropped frames, server. Horizontal or vertical.

export class StreamStats {
    private element: HTMLDivElement
    private timer: ReturnType<typeof setInterval> | undefined
    private level = 0
    private vertical = false
    private left = false
    private server = ''
    private last = { bytes: 0, time: 0, decode: 0, frames: 0, lost: 0, received: 0 }

    constructor(private pc: RTCPeerConnection) {
        this.element = document.createElement('div')
        Object.assign(this.element.style, {
            position: 'absolute', top: '8px', right: '8px', zIndex: '3', padding: '4px 8px', borderRadius: '6px',
            background: 'rgba(0,0,0,0.55)', color: '#e8e8e8', font: '12px monospace', lineHeight: '16px',
            pointerEvents: 'none', display: 'none', whiteSpace: 'pre', textAlign: 'right',
        })
        document.body.appendChild(this.element)
    }

    show(level: number, vertical: boolean, server: string, left = false) {
        this.level = level; this.vertical = vertical; this.server = server; this.left = left
        // Top left keeps clear of the clock Xbox draws top right while its guide is open.
        Object.assign(this.element.style, left ? { left: '8px', right: '', textAlign: 'left' } : { left: '', right: '8px', textAlign: 'right' })
        this.element.style.display = level > 0 ? 'block' : 'none'
        if (level > 0 && this.timer === undefined) { this.timer = setInterval(() => this.update(), 1000); this.update() }
        if (level === 0 && this.timer !== undefined) { clearInterval(this.timer); this.timer = undefined }
    }

    private async update() {
        const report = await this.pc.getStats()
        let video: any, pair: any
        const codecs = new Map<string, any>()
        report.forEach((s: any) => {
            if (s.type === 'inbound-rtp' && s.kind === 'video') video = s
            else if (s.type === 'candidate-pair' && s.state === 'succeeded' && s.nominated) pair = s
            else if (s.type === 'codec') codecs.set(s.id, s)
        })
        if (video === undefined) return
        const seconds = this.last.time > 0 ? (video.timestamp - this.last.time) / 1000 : 0
        const mbps = seconds > 0 ? (video.bytesReceived - this.last.bytes) * 8 / seconds / 1e6 : 0
        const frames = (video.framesDecoded ?? 0) - this.last.frames
        const decodeMs = frames > 0 ? ((video.totalDecodeTime ?? 0) - this.last.decode) * 1000 / frames : 0
        const lost = (video.packetsLost ?? 0) - this.last.lost
        const received = (video.packetsReceived ?? 0) - this.last.received
        const loss = lost + received > 0 ? lost * 100 / (lost + received) : 0
        this.last = { bytes: video.bytesReceived, time: video.timestamp, decode: video.totalDecodeTime ?? 0,
            frames: video.framesDecoded ?? 0, lost: video.packetsLost ?? 0, received: video.packetsReceived ?? 0 }

        const items: string[] = ['web', `${Math.round(video.framesPerSecond ?? 0)} fps`]
        if (this.level >= 2) {
            if (pair?.currentRoundTripTime !== undefined) items.push(`ping ${Math.round(pair.currentRoundTripTime * 1000)} ms`)
            items.push(`${video.frameWidth ?? '?'}x${video.frameHeight ?? '?'}`)
        }
        if (this.level >= 3) items.push(`${mbps.toFixed(1)} Mb/s`, `decode ${decodeMs.toFixed(1)} ms`, `loss ${loss.toFixed(1)}%`)
        if (this.level >= 4) {
            const codec = codecs.get(video.codecId)
            if (codec) items.push(String(codec.mimeType ?? '').replace('video/', ''))
            items.push(`jitter ${Math.round((video.jitter ?? 0) * 1000)} ms`, `dropped ${video.framesDropped ?? 0}`)
            if (this.server) items.push(this.server)
        }
        this.element.textContent = items.join(this.vertical ? '\n' : '  ·  ')
    }

    destroy() { this.show(0, this.vertical, this.server, this.left); this.element.remove() }
}

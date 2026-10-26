// Offer tweaks after Better xCloud by redphx (MIT, src/utils/sdp.ts): prefer an H.264 profile, cap bitrate.

/** Moves the H.264 payloads of the chosen profile to the front of the video m-line. */
export function preferH264Profile(sdp: string, profile: 'high' | 'normal' | 'low'): string {
    const prefix = profile === 'high' ? '4d' : (profile === 'low' ? '420' : '42e')
    const preferred: string[] = []
    for (const match of sdp.matchAll(/a=fmtp:(\d+).*profile-level-id=([0-9a-f]{6})/g)) {
        if (match[2].startsWith(prefix)) preferred.push(match[1])
    }
    if (preferred.length === 0) return sdp
    const lines = sdp.split('\r\n')
    const index = lines.findIndex(line => line.startsWith('m=video'))
    if (index < 0) return sdp
    const parts = lines[index].trim().split(' ')
    const ids = parts.slice(3).filter(id => !preferred.includes(id))
    lines[index] = parts.slice(0, 3).concat(preferred, ids).join(' ')
    return lines.join('\r\n')
}

/** Sets b=AS (kbit/s) on the video m-section. */
export function capVideoBitrate(sdp: string, kbps: number): string {
    const lines = sdp.split('\r\n')
    const start = lines.findIndex(line => line.startsWith('m=video'))
    if (start < 0) return sdp
    const bLine = `b=AS:${kbps}`
    for (let i = start + 1; i <= lines.length; i++) {
        const line = lines[i]
        if (line === undefined || line.startsWith('m=')) { lines.splice(i, 0, bLine); break }
        if (line.startsWith('i=') || line.startsWith('c=')) continue
        if (line.startsWith('b=AS:')) { lines[i] = bLine; break }
        lines.splice(i, 0, bLine); break
    }
    return lines.join('\r\n')
}

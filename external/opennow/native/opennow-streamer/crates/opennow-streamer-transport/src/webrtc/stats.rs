use super::*;
use str0m::stats::{MediaIngressStats, PeerStats};

const PING_TIMEOUT: Duration = Duration::from_secs(5);

#[derive(Debug, Clone, Copy, Default)]
pub struct TransportStats {
    pub ping_ms: Option<f64>,
    pub jitter_ms: Option<f64>,
    pub packet_loss_percent: Option<f64>,
    pub socket_receive_bytes: Option<u64>,
}

pub(super) struct Stats {
    video_mid: Mid,
    video_clock_rate: Frequency,
    previous_responses: u64,
    ice_ping: Option<(Instant, Duration)>,
    peer_loss: Option<f32>,
    video_loss: Option<f32>,
    latest: TransportStats,
}

impl Stats {
    pub(super) fn new(video_mid: Mid, video_clock_rate: Frequency) -> Self {
        Self {
            video_mid,
            video_clock_rate,
            previous_responses: 0,
            ice_ping: None,
            peer_loss: None,
            video_loss: None,
            latest: TransportStats::default(),
        }
    }

    pub(super) fn peer(&mut self, stats: PeerStats) -> TransportStats {
        match stats.selected_candidate_pair {
            None => {
                self.previous_responses = 0;
                self.ice_ping = None;
            }
            Some(pair) if pair.responses_received != self.previous_responses => {
                self.previous_responses = pair.responses_received;
                self.ice_ping = pair
                    .current_round_trip_time
                    .map(|rtt| (stats.timestamp, rtt));
            }
            Some(_) => {}
        }
        self.latest.ping_ms = self
            .ice_ping
            .filter(|(at, _)| stats.timestamp.saturating_duration_since(*at) < PING_TIMEOUT)
            .map(|(_, rtt)| rtt.as_secs_f64() * 1_000.0);
        self.latest.socket_receive_bytes = Some(stats.peer_bytes_rx);
        self.peer_loss = stats.ingress_loss_fraction;
        self.latest.packet_loss_percent = self
            .video_loss
            .or(self.peer_loss)
            .map(|loss| f64::from(loss) * 100.0);
        self.latest
    }

    pub(super) fn media(&mut self, stats: MediaIngressStats) -> Option<TransportStats> {
        if stats.mid != self.video_mid {
            return None;
        }
        self.latest.jitter_ms =
            Some(f64::from(stats.jitter) * 1_000.0 / f64::from(self.video_clock_rate.get()));
        self.video_loss = stats.loss;
        self.latest.packet_loss_percent = self
            .video_loss
            .or(self.peer_loss)
            .map(|loss| f64::from(loss) * 100.0);
        Some(self.latest)
    }
}

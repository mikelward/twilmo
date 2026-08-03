// Twilmo's inbound routing webhook (docs/twilio-setup.md). Deployed
// PROTECTED — only Twilio calls it (the number's voice webhook), and
// protection makes the runtime verify Twilio's request signature.
//
// It dials the app's client identity: Twilio holds the call and sends the
// high-priority FCM push itself, which is the whole inbound design (SPEC →
// "Inbound — the push-woken ring"). This Function sits ahead of the ring,
// so it does the minimum: one <Dial><Client>.
//
// No-answer behavior: the dial times out after RING_TIMEOUT_SECONDS and the
// caller gets Twilio's default hangup. What should happen instead
// (voicemail, a message, a forward) is an open product question in SPEC.md;
// this default is deliberately the simplest honest behavior until decided.

const RING_TIMEOUT_SECONDS = 30;

exports.handler = (context, event, callback) => {
  const twiml = new Twilio.twiml.VoiceResponse();
  const dial = twiml.dial({
    // Ring the caller's side only when the app has actually answered, so
    // the caller keeps hearing ringback (not silence) while the phone
    // wakes, and unanswered pushes never bill a connected leg.
    answerOnBridge: true,
    timeout: RING_TIMEOUT_SECONDS,
  });
  dial.client(context.CLIENT_IDENTITY);
  return callback(null, twiml);
};

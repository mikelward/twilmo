// Twilmo's outbound routing webhook (docs/twilio-setup.md). Deployed
// PROTECTED — Twilio invokes the TwiML App's voice URL when the app calls
// Voice.connect, asking how to route the call; protection makes the runtime
// verify Twilio's request signature.
//
// Contract (pinned in the doc): the app passes the dialed number, E.164, as
// the To connect parameter; the call presents the account's own number
// (CALLER_ID env var) as caller ID.
//
// Nothing here logs: event.To is the dialed number, which never enters a
// log line or error message (AGENTS.md Privacy).

exports.handler = (context, event, callback) => {
  const twiml = new Twilio.twiml.VoiceResponse();
  const to = event.To;
  if (!to) {
    // Missing connect parameter — a buggy client. End the leg; the SDK
    // reports the connect failure and the app surfaces it at dial time.
    twiml.hangup();
    return callback(null, twiml);
  }
  const dial = twiml.dial({ callerId: context.CALLER_ID });
  dial.number(to);
  return callback(null, twiml);
};

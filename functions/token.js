// Twilmo's access-token endpoint (docs/twilio-setup.md). Deployed PUBLIC —
// the caller is the app, not Twilio, so signature protection would reject
// every legitimate request. It authenticates callers itself: the request
// must carry the shared secret in the x-twilmo-secret header.
//
// Response contract (the app depends on this shape):
//   200 { "token": "<jwt>", "expiresInSeconds": 3600, "identity": "<id>" }
//   401 for a missing or wrong secret.

const crypto = require('crypto');

const TOKEN_TTL_SECONDS = 3600;

// Constant-time comparison so the secret can't be probed byte by byte.
// Hash both sides first: timingSafeEqual requires equal lengths, and the
// length of the real secret must not itself leak through a fast-fail.
function secretsMatch(provided, expected) {
  const a = crypto.createHash('sha256').update(String(provided)).digest();
  const b = crypto.createHash('sha256').update(String(expected)).digest();
  return crypto.timingSafeEqual(a, b);
}

exports.handler = (context, event, callback) => {
  const headers = (event.request && event.request.headers) || {};
  const provided = headers['x-twilmo-secret'] || '';

  if (!context.SHARED_SECRET || !secretsMatch(provided, context.SHARED_SECRET)) {
    const response = new Twilio.Response();
    response.setStatusCode(401);
    response.appendHeader('Content-Type', 'application/json');
    // No detail in the body: a caller without the secret learns nothing.
    response.setBody(JSON.stringify({ error: 'unauthorized' }));
    return callback(null, response);
  }

  const AccessToken = Twilio.jwt.AccessToken;
  const VoiceGrant = AccessToken.VoiceGrant;

  const token = new AccessToken(
    context.ACCOUNT_SID,
    context.API_KEY_SID,
    context.API_KEY_SECRET,
    { identity: context.CLIENT_IDENTITY, ttl: TOKEN_TTL_SECONDS },
  );
  token.addGrant(
    new VoiceGrant({
      outgoingApplicationSid: context.TWIML_APP_SID,
      // Required for Voice.register to accept this identity for incoming
      // calls; the push credential alone does not grant incoming.
      incomingAllow: true,
      pushCredentialSid: context.PUSH_CREDENTIAL_SID,
    }),
  );

  const response = new Twilio.Response();
  response.appendHeader('Content-Type', 'application/json');
  response.setBody(
    JSON.stringify({
      token: token.toJwt(),
      expiresInSeconds: TOKEN_TTL_SECONDS,
      identity: context.CLIENT_IDENTITY,
    }),
  );
  return callback(null, response);
};

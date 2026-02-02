// Handles JWT token generation using Web Crypto API

const browserAPI = typeof browser !== 'undefined' ? browser : chrome;
const JWT_SECRET = 'overflowy2mate';

// Base64URL encode
function base64urlEncode(data) {
  const base64 = btoa(String.fromCharCode(...new Uint8Array(data)));
  return base64.replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

// Base64URL encode string
function base64urlEncodeString(str) {
  const base64 = btoa(str);
  return base64.replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

// Generate JWT token using Web Crypto API
async function generateJWT() {
  try {
    const header = { alg: 'HS256', typ: 'JWT' };
    const now = Math.floor(Date.now() / 1000);
    const payload = {
      authorized: true,
      timestamp: Date.now(),
      iat: now,
      exp: now + 180
    };

    const encodedHeader = base64urlEncodeString(JSON.stringify(header));
    const encodedPayload = base64urlEncodeString(JSON.stringify(payload));
    const signatureInput = `${encodedHeader}.${encodedPayload}`;

    const encoder = new TextEncoder();
    const keyData = encoder.encode(JWT_SECRET);
    const messageData = encoder.encode(signatureInput);

    const cryptoKey = await crypto.subtle.importKey(
      'raw', keyData, { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']
    );

    const signature = await crypto.subtle.sign('HMAC', cryptoKey, messageData);
    const encodedSignature = base64urlEncode(signature);

    return { success: true, token: `${encodedHeader}.${encodedPayload}.${encodedSignature}` };
  } catch (error) {
    return { success: false, error: error.message };
  }
}

// Message listener
browserAPI.runtime.onMessage.addListener((request, _sender, sendResponse) => {
  if (request.action === 'generateToken') {
    generateJWT().then(result => sendResponse(result));
    return true;
  }
});


// Public keys only. Signature verification is supplied by the existing P256 verifier.
export function ratchetTranscript(bundle) {
  if (!bundle || typeof bundle !== 'object' || Array.isArray(bundle) ||
      Object.keys(bundle).sort().join(',') !== 'generation,identity,oneTime,owner,peer,signature' ||
      !/^[a-f0-9]{64}$/.test(bundle.owner ?? '') || !/^[a-f0-9]{64}$/.test(bundle.peer ?? '') ||
      bundle.owner === bundle.peer ||
      !/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/.test(bundle.generation ?? '') ||
      typeof bundle.signature !== 'string' || bundle.signature.length < 80 || bundle.signature.length > 100)
    throw new Error('Invalid public bundle');
  for (const name of ['identity','oneTime']) {
    const value=bundle[name];
    if(typeof value !== 'string' || !/^[A-Za-z0-9+/]{43}$/.test(value)) throw new Error('Invalid curve key');
    const bytes=Uint8Array.from(atob(value+'='),c=>c.charCodeAt(0));
    if(bytes.length!==32 || !bytes.some(x=>x!==0) || btoa(String.fromCharCode(...bytes)).replace(/=+$/,'')!==value)
      throw new Error('Invalid curve key');
  }
  return 'LANU-OLM2-BUNDLE-v1\n'+['owner','peer','generation','identity','oneTime'].map(k=>bundle[k]).join('\n');
}

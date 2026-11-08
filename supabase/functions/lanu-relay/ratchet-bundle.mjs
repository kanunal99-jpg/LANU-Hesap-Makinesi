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

const HEX=/^[a-f0-9]{64}$/;
const UUID=/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/;
export function ratchetResetTranscript(owner,peer,resetId,previousEpoch,bundle) {
  if(!HEX.test(owner??'') || !HEX.test(peer??'') || owner===peer ||
     !UUID.test(resetId??'') || !HEX.test(previousEpoch??'') ||
     bundle?.owner!==owner || bundle?.peer!==peer) throw new Error('Invalid session renewal');
  return 'LANU-OLM2-RESET-v1\n'+[owner,peer,resetId,previousEpoch,ratchetTranscript(bundle)].join('\n');
}
export async function ratchetEpoch(first,second) {
  if(first?.owner!==second?.peer || first?.peer!==second?.owner) throw new Error('Invalid pair');
  const ordered=[first,second].sort((a,b)=>a.owner.localeCompare(b.owner));
  const text='LANU-OLM2-PAIR-v1\n'+ordered.map(ratchetTranscript).join('\n');
  return [...new Uint8Array(await crypto.subtle.digest('SHA-256',new TextEncoder().encode(text)))]
    .map(v=>v.toString(16).padStart(2,'0')).join('');
}

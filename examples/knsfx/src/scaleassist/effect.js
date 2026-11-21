// Scale Assist: escala a camada em X, em Y, nos dois, ou em X com o inverso em Y (squash & stretch).
function animate(env, el, p) {
  if (p.axis == 0) el.transform.scale.x *= p.scale;
  else if (p.axis == 1) el.transform.scale.y *= p.scale;
  else if (p.axis == 2) { el.transform.scale.x *= p.scale; el.transform.scale.y *= p.scale * p.damp; }
  else if (p.axis == 3) { el.transform.scale.x *= p.scale; el.transform.scale.y *= 1.0 - ((p.scale - 1.0) * p.damp); }
  return el;
}

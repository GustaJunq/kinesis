// Escalonar seleção: a primeira camada selecionada fica onde está e cada uma das outras começa
// `intervalo` segundos depois da anterior.
function escalonar(ctx) {
  var gap = Number(ctx.params.intervalo);
  if (!(gap >= 0)) gap = 0.2;
  var ids = ctx.selection;
  if (ids.length < 2) return { message: "Selecione duas camadas ou mais." };
  var layers = ctx.project.layers;
  var first = null, n = 0;
  for (var i = 0; i < ids.length; i++) {
    for (var j = 0; j < layers.length; j++) {
      if (layers[j].id !== ids[i]) continue;
      if (first === null) first = layers[j].start;
      layers[j].start = first + gap * n;
      n++;
    }
  }
  kinesis.log("camadas escalonadas: " + n);
  return { project: ctx.project, message: n + " camadas escalonadas" };
}

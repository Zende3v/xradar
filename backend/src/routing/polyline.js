/** Forme polyline6 Valhalla en coordonnées [lon, lat]. */
export function decodePolyline6(encoded) {
  if (typeof encoded !== 'string' || !encoded) throw new Error('route shape missing');
  let index = 0;
  const next = () => {
    let result = 0;
    for (let shift = 0; ; shift += 5) {
      // Sept blocs maximum : déjà au-delà de 180° à six décimales.
      if (shift > 30) throw new Error('route shape unreadable');
      if (index >= encoded.length) throw new Error('route shape cut short');
      const chunk = encoded.charCodeAt(index++) - 63;
      if (chunk < 0 || chunk > 63) throw new Error('route shape unreadable');
      result += (chunk & 0x1f) * 2 ** shift;
      if (chunk < 0x20) break;
    }
    return result % 2 ? -(result + 1) / 2 : result / 2;
  };
  const points = [];
  let lat = 0;
  let lon = 0;
  while (index < encoded.length) {
    lat += next();
    lon += next();
    const point = [lon / 1e6, lat / 1e6];
    if (Math.abs(point[0]) > 180 || Math.abs(point[1]) > 90) throw new Error('route shape off the globe');
    points.push(point);
  }
  if (points.length < 2) throw new Error('route shape too short');
  return points;
}

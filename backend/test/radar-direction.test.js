import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { controlledCourse, readDirection, townQuery } from '../src/radars/direction.js';
import { quietCourseOf } from '../src/radars/votes.js';

describe('radar direction', () => {
  it('reads the official texts: towns, winds, nothing', () => {
    assert.deepEqual(readDirection('ST FERREOL D AUROURE vers ST ETIENNE'), { from: 'Saint Ferreol D Auroure', to: 'Saint Etienne' });
    assert.deepEqual(readDirection('ROCHE SUR YON (LA) vers HERBIERS (LES)'), { from: 'La Roche Sur Yon', to: 'Les Herbiers' });
    assert.deepEqual(readDirection('Sud vers Nord'), { wind: 0 });
    assert.deepEqual(readDirection('Nord Ouest vers Sud Est'), { wind: 135 });
    assert.deepEqual(readDirection('Est vers Ouest'), { wind: 270 });
    assert.deepEqual(readDirection("Route de Gisors (RD 105) vers l'Est"), { wind: 90 });
    assert.deepEqual(readDirection('Av. du 11 novembre 1918 vers la rocade'), null);
    for (const nothing of ['PR croissant', '-', '', null, 'BLD BONREPOS vers BLD DE LA MARQUETTE']) {
      assert.equal(readDirection(nothing), null);
    }
    assert.equal(townQuery('L AIGLE'), 'L Aigle');
  });

  it('turns the road toward the text, never across it', () => {
    const radar = { lat: 46.0, lon: 4.0 };
    const north = { lat: 46.5, lon: 4.0 };
    const south = { lat: 45.5, lon: 4.0 };
    // A road running north-south (given as 180°): toward the northern town, 0°.
    assert.equal(controlledCourse({ reading: { from: 'S', to: 'N' }, from: south, to: north, radar, roadCourse: 180 }), 0);
    // Only the second town found: from the radar to it.
    assert.equal(controlledCourse({ reading: { from: 'X', to: 'S' }, to: south, radar, roadCourse: 2 }), 182);
    assert.equal(controlledCourse({ reading: { wind: 90 }, radar, roadCourse: 265 }), 85);
    // The road runs east-west, the text says north: no way decided.
    assert.equal(controlledCourse({ reading: { wind: 0 }, radar, roadCourse: 90 }), null);
    // A town at the radar says nothing about the way.
    assert.equal(controlledCourse({ reading: { from: null, to: 'Here' }, to: { lat: 46.001, lon: 4.0 }, radar, roadCourse: 0 }), null);
  });

  it('makes a radar quiet one way once drivers agree', () => {
    assert.equal(quietCourseOf([{ course: 90, weight: 1 }]), null);
    assert.equal(quietCourseOf([{ course: 90, weight: 1 }, { course: 270, weight: 1 }]), null);
    assert.equal(quietCourseOf([{ course: 350, weight: 1 }, { course: 10, weight: 1 }]), 0);
    assert.equal(quietCourseOf([{ course: 180, weight: 2 }]), 180);
  });
});

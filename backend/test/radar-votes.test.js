import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { quietCourseOf } from '../src/radars/votes.js';

describe('radar "Pas dans mon sens"', () => {
  it('makes a radar quiet one way once drivers agree', () => {
    assert.equal(quietCourseOf([{ course: 90, weight: 1 }]), null);
    assert.equal(quietCourseOf([{ course: 90, weight: 1 }, { course: 270, weight: 1 }]), null);
    assert.equal(quietCourseOf([{ course: 350, weight: 1 }, { course: 10, weight: 1 }]), 0);
    assert.equal(quietCourseOf([{ course: 180, weight: 2 }]), 180);
  });
});

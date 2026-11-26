import type { MergePolicy } from '../../api/types';

/** Short names of the merge policies (ARCHITECTURE §6 "Merge policy"). */
export const MERGE_POLICY_LABEL: Record<MergePolicy, string> = {
  automatic: 'Automatic',
  owner_approves: 'Owner approves',
  github: 'Merge on GitHub',
};

/** One line on what each policy means, shown on the project page and in its settings dialog. */
export const MERGE_POLICY_EXPLANATION: Record<MergePolicy, string> = {
  automatic: 'the approver agent may merge.',
  owner_approves: 'every merge waits for the run owner’s approval in Agern.',
  github: 'Agern never merges; the team reviews and merges pull requests on GitHub.',
};

/** "Automatic — the approver agent may merge." */
export const mergePolicySentence = (p: MergePolicy) => `${MERGE_POLICY_LABEL[p]} — ${MERGE_POLICY_EXPLANATION[p]}`;
